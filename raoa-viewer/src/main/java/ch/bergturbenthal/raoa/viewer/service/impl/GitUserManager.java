package ch.bergturbenthal.raoa.viewer.service.impl;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.impl.Limiter;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AccessRequest;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AuthenticationId;
import ch.bergturbenthal.raoa.viewer.model.usermanager.User;
import ch.bergturbenthal.raoa.viewer.service.UserManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Function;
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
  private final Mono<UUID> metaIdMono;
  private final ObjectReader userReader;
  private final ObjectWriter userWriter;
  private final Limiter limiter;

  public GitUserManager(AlbumList albumList, final Limiter limiter) {
    this.albumList = albumList;
    this.limiter = limiter;
    final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().indentOutput(true).build();

    userReader = objectMapper.readerFor(User.class);
    userWriter = objectMapper.writerFor(User.class);

    metaIdMono =
        albumList
            .listAlbums()
            .filterWhen(a -> a.getAccess().getFullPath().map(p -> p.equals(".meta")))
            // .log("meta album")
            .map(AlbumList.FoundAlbum::getAlbumId)
            .singleOrEmpty();
  }

  @Override
  public Mono<User> createNewUser(final AccessRequest foundRequest) {
    try {
      final User newUser =
          User.builder()
              .userData(foundRequest.getUserData())
              .superuser(false)
              .id(UUID.randomUUID())
              .authentications(Collections.singleton(foundRequest.getAuthenticationId()))
              .visibleAlbums(
                  foundRequest.getRequestedAlbum() == null
                      ? Collections.emptySet()
                      : Collections.singleton(foundRequest.getRequestedAlbum()))
              .build();
      final String userFileName = createUserFile(newUser.getId());
      final File tempFile = File.createTempFile(newUser.getId().toString(), "json");
      userWriter.writeValue(tempFile, newUser);
      return overrideFile(userFileName, tempFile, "created user", false)
          .filter(t -> t)
          .map(t -> newUser);
    } catch (IOException e) {
      return Mono.error(e);
    }
  }

  @NotNull
  public Mono<Boolean> overrideFile(
      final String newFilename,
      final File srcData,
      final String commitComment,
      final boolean replaceIfExists) {
    return metaIdMono
        .flatMap(albumList::getAlbum)
        .flatMap(GitAccess::createUpdater)
        .flatMap(
            u ->
                u.importFile(srcData.toPath(), newFilename, replaceIfExists)
                    .filter(t -> t)
                    .flatMap(t -> u.commit(commitComment))
                    .filter(t -> t)
                    .doFinally(
                        signal -> {
                          u.close();
                          srcData.delete();
                        }));
  }

  @Override
  public Mono<Boolean> removeUser(final UUID id) {
    final String userFileName = createUserFile(id);
    return metaIdMono
        .flatMap(albumList::getAlbum)
        .flatMap(GitAccess::createUpdater)
        .flatMap(
            u -> u.removeFile(userFileName).filter(t -> t).flatMap(t -> u.commit("removed user")))
        .defaultIfEmpty(false);
  }

  @Override
  public void assignNewIdentity(final UUID existingId, final AuthenticationId baseRequest) {}

  @NotNull
  private synchronized Flux<User> allUsers() {
    return loadUsers(ALL_USERS_FILTER).cache(CACHE_DURATION);
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
  public Mono<User> loadUser(final UUID userId) {
    final PathFilter filter = PathFilter.create(createUserFile(userId));
    return loadUsers(filter).singleOrEmpty();
  }

  @Override
  public Mono<User> updateUser(
      final UUID userId, final Function<User, User> updater, final String updateDescription) {
    final String userFile = createUserFile(userId);
    final PathFilter filter = PathFilter.create(userFile);

    return loadUsers(filter)
        .map(updater)
        .singleOrEmpty()
        .flatMap(
            updatedUser -> {
              try {
                final File tempFile = File.createTempFile(userId.toString(), "json");
                userWriter.writeValue(tempFile, updatedUser);
                return overrideFile(userFile, tempFile, updateDescription, true)
                    .filter(t -> t)
                    .map(t -> updatedUser);
              } catch (IOException e) {
                return Mono.error(
                    new RuntimeException("Cannot write user " + userId + " for update", e));
              }
            });
  }
}
