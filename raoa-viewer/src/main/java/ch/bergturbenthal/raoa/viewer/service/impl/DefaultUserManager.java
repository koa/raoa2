package ch.bergturbenthal.raoa.viewer.service.impl;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AccessRequest;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AuthenticationId;
import ch.bergturbenthal.raoa.viewer.model.usermanager.User;
import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.UserManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class DefaultUserManager implements UserManager {
  private static final TreeFilter ALL_USERS_FILTER =
      AndTreeFilter.create(PathFilter.create("users"), PathSuffixFilter.create(".json"));
  private static final Duration CACHE_DURATION = Duration.ofMinutes(5);
  @org.jetbrains.annotations.NotNull private final AlbumList albumList;
  private final ViewerProperties viewerProperties;
  private final Mono<UUID> metaIdMono;
  private final ObjectReader userReader;
  private final ObjectReader accessRequestObjectReader;
  private ObjectWriter accessRequestObjectWriter;
  private Map<AuthenticationId, Mono<User>> userByAuthenticationCache =
      Collections.synchronizedMap(new HashMap<>());
  private Map<UUID, Mono<User>> userByIdCache = Collections.synchronizedMap(new HashMap<>());
  private AtomicReference<Flux<User>> allUsersCache = new AtomicReference<>();
  private AtomicReference<Set<AuthenticationId>> pendingAuthenticationsReference =
      new AtomicReference<>(Collections.emptySet());

  public DefaultUserManager(AlbumList albumList, ViewerProperties viewerProperties) {
    this.albumList = albumList;
    this.viewerProperties = viewerProperties;
    final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().indentOutput(true).build();
    accessRequestObjectWriter = objectMapper.writerFor(AccessRequest.class);
    accessRequestObjectReader = objectMapper.readerFor(AccessRequest.class);

    userReader = objectMapper.readerFor(User.class);

    metaIdMono =
        albumList
            .listAlbums()
            .filterWhen(a -> a.getAccess().getFullPath().map(p -> p.equals(".meta")))
            // .log("meta album")
            .map(AlbumList.FoundAlbum::getAlbumId)
            .singleOrEmpty()
            .cache(CACHE_DURATION);
    final File requestDir = getAccessRequestsDir();
    if (requestDir.exists()) {
      try {
        final Set<AuthenticationId> pendingRequests = new HashSet<>();
        final File[] files = requestDir.listFiles();
        if (files != null)
          for (File file : files) {
            pendingRequests.add(
                accessRequestObjectReader.<AccessRequest>readValue(file).getAuthenticationId());
          }
        pendingAuthenticationsReference.set(Collections.unmodifiableSet(pendingRequests));
      } catch (IOException e) {
        log.warn("Cannot load existing requests", e);
      }
    }
  }

  @Override
  public void requestAccess(final AccessRequest request) {
    final File resultFile = accessRequestFile(request.getAuthenticationId());
    try {
      accessRequestObjectWriter.writeValue(resultFile, request);
      pendingAuthenticationsReference.updateAndGet(
          oldIds -> {
            final HashSet<AuthenticationId> newIds = new HashSet<>(oldIds);
            newIds.add(request.getAuthenticationId());
            return Collections.unmodifiableSet(newIds);
          });
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @NotNull
  private File accessRequestFile(final AuthenticationId authenticationId) {
    String filename = authenticationId.getAuthority() + "-" + authenticationId.getId();
    return new File(getAccessRequestsDir(), filename);
  }

  @Override
  public Mono<Boolean> createNewUser(final AuthenticationId baseRequest) {
    final File file = accessRequestFile(baseRequest);
    if (file.exists()) {
      // metaIdMono.flatMap(albumList::getAlbum).map(a ->{

      // });
      file.delete();
    }
    return null;
  }

  @Override
  public void assignNewIdentity(final UUID existingId, final AuthenticationId baseRequest) {}

  @NotNull
  private File getAccessRequestsDir() {
    final File requestDir = new File(viewerProperties.getDataDir(), "access-requests");
    if (!requestDir.exists()) requestDir.mkdirs();
    return requestDir;
  }

  @Override
  public Mono<User> findUserForAuthentication(final AuthenticationId authenticationId) {
    if (userByAuthenticationCache.size() > 100) userByAuthenticationCache.clear();
    return userByAuthenticationCache.computeIfAbsent(
        authenticationId,
        k ->
            allUsers()
                .filter(u -> u.getAuthentications() != null && u.getAuthentications().contains(k))
                .singleOrEmpty()
                .cache(CACHE_DURATION));
  }

  @NotNull
  private synchronized Flux<User> allUsers() {
    final Flux<User> cachedUsers = allUsersCache.get();
    if (cachedUsers != null) return cachedUsers;
    final Flux<User> userFlux = loadUsers(ALL_USERS_FILTER).cache(CACHE_DURATION);
    allUsersCache.set(userFlux);
    return userFlux;
  }

  @NotNull
  private Flux<User> loadUsers(final TreeFilter filter) {
    return metaIdMono
        .flatMap(albumList::getAlbum)
        .flatMapMany(
            a ->
                a.listFiles(filter)
                    .flatMap(
                        e ->
                            a.readObject(e.getFileId())
                                .map(
                                    loader -> {
                                      try {
                                        return userReader.readValue(loader.getBytes());
                                      } catch (IOException ex) {
                                        throw new RuntimeException(ex);
                                      }
                                    })));
  }

  @Override
  public Mono<User> findUserById(final UUID id) {
    if (userByIdCache.size() > 1000) userByIdCache.clear();
    return userByIdCache.computeIfAbsent(
        id,
        k -> loadUsers(PathFilter.create(createUserFile(k))).singleOrEmpty().cache(CACHE_DURATION));
  }

  @NotNull
  private String createUserFile(final UUID id) {
    return "users/" + id.toString() + ".json";
  }

  @Override
  public Collection<User> listUsers() {
    return null;
  }

  @Override
  public Collection<User> listUserForAlbum(final UUID albumId) {
    return null;
  }

  @Override
  public Collection<AccessRequest> listPendingRequests() {
    return null;
  }

  @Override
  public boolean hasPendingRequest(final AuthenticationId id) {
    return pendingAuthenticationsReference.get().contains(id);
  }
}
