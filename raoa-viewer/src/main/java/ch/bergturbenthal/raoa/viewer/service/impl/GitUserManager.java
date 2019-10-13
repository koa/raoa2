package ch.bergturbenthal.raoa.viewer.service.impl;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.impl.Limiter;
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
import java.util.stream.Collectors;
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
public class GitUserManager implements UserManager {
  private static final TreeFilter ALL_USERS_FILTER =
      AndTreeFilter.create(PathFilter.create("users"), PathSuffixFilter.create(".json"));
  private static final Duration CACHE_DURATION = Duration.ofMinutes(5);
  @org.jetbrains.annotations.NotNull private final AlbumList albumList;
  private final ViewerProperties viewerProperties;
  private final Mono<UUID> metaIdMono;
  private final ObjectReader userReader;
  private final ObjectReader accessRequestObjectReader;
  private final ObjectWriter userWriter;
  private final Limiter limiter;
  private ObjectWriter accessRequestObjectWriter;
  private Map<AuthenticationId, Mono<User>> userByAuthenticationCache =
      Collections.synchronizedMap(new HashMap<>());
  private Map<UUID, Mono<User>> userByIdCache = Collections.synchronizedMap(new HashMap<>());
  private AtomicReference<Flux<User>> allUsersCache = new AtomicReference<>();
  private AtomicReference<Set<AuthenticationId>> pendingAuthenticationsReference =
      new AtomicReference<>(Collections.emptySet());

  public GitUserManager(
      AlbumList albumList, ViewerProperties viewerProperties, final Limiter limiter) {
    this.albumList = albumList;
    this.viewerProperties = viewerProperties;
    this.limiter = limiter;
    final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().indentOutput(true).build();
    accessRequestObjectWriter = objectMapper.writerFor(AccessRequest.class);
    accessRequestObjectReader = objectMapper.readerFor(AccessRequest.class);

    userReader = objectMapper.readerFor(User.class);
    userWriter = objectMapper.writerFor(User.class);

    metaIdMono =
        albumList
            .listAlbums()
            .filterWhen(a -> a.getAccess().getFullPath().map(p -> p.equals(".meta")))
            // .log("meta album")
            .map(AlbumList.FoundAlbum::getAlbumId)
            .singleOrEmpty();
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
  public Mono<User> createNewUser(final AuthenticationId baseRequest) {
    try {
      final File file = accessRequestFile(baseRequest);
      if (file.exists()) {
        final AccessRequest foundRequest = accessRequestObjectReader.readValue(file);
        final User newUser =
            User.builder()
                .userData(foundRequest.getUserData())
                .superuser(false)
                .id(UUID.randomUUID())
                .authentications(Collections.singleton(baseRequest))
                .visibleAlbums(
                    foundRequest.getRequestedAlbum() == null
                        ? Collections.emptySet()
                        : Collections.singleton(foundRequest.getRequestedAlbum()))
                .build();
        final String userFileName = createUserFile(newUser.getId());
        final File tempFile = File.createTempFile(newUser.getId().toString(), "json");
        userWriter.writeValue(tempFile, newUser);
        return metaIdMono
            .flatMap(albumList::getAlbum)
            .flatMap(GitAccess::createUpdater)
            .flatMap(
                u ->
                    u.importFile(tempFile.toPath(), userFileName)
                        .filter(t -> t)
                        .flatMap(t -> u.commit("created user"))
                        .filter(t -> t)
                        .doFinally(
                            signal -> {
                              u.close();
                              tempFile.delete();
                            }))
            .map(t -> newUser)
            .doOnNext(u -> file.delete())
            .doFinally(
                signal -> {
                  userByIdCache.clear();
                  userByAuthenticationCache.clear();
                  allUsersCache.set(null);
                });
      }
      return Mono.empty();
    } catch (IOException e) {
      return Mono.error(e);
    }
  }

  @Override
  public void assignNewIdentity(final UUID existingId, final AuthenticationId baseRequest) {}

  @NotNull
  private File getAccessRequestsDir() {
    final File requestDir = new File(viewerProperties.getDataDir(), "access-requests");
    if (!requestDir.exists()) requestDir.mkdirs();
    return requestDir;
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
                            limiter.limit(
                                a.readObject(e.getFileId())
                                    .map(
                                        loader -> {
                                          try {
                                            return userReader.readValue(loader.getBytes());
                                          } catch (IOException ex) {
                                            throw new RuntimeException(ex);
                                          }
                                        }),
                                "load user ")));
  }

  @NotNull
  private String createUserFile(final UUID id) {
    return "users/" + id.toString() + ".json";
  }

  @Override
  public Flux<User> listUsers() {
    return allUsers();
  }

  @Override
  public Collection<AccessRequest> listPendingRequests() {
    final Collection<AccessRequest> ret = new ArrayList<>();
    final File requestDir = getAccessRequestsDir();
    if (requestDir.exists()) {
      try {
        final Set<AuthenticationId> pendingRequests = new HashSet<>();
        final File[] files = requestDir.listFiles();
        if (files != null)
          for (File file : files) {
            ret.add(accessRequestObjectReader.readValue(file));
          }
        pendingAuthenticationsReference.set(
            Collections.unmodifiableSet(
                ret.stream().map(AccessRequest::getAuthenticationId).collect(Collectors.toSet())));
      } catch (IOException e) {
        log.warn("Cannot load existing requests", e);
      }
    }
    return ret;
  }
}
