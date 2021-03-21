package ch.bergturbenthal.raoa.elastic.service.impl;

import ch.bergturbenthal.raoa.elastic.model.AuthenticationId;
import ch.bergturbenthal.raoa.elastic.model.Group;
import ch.bergturbenthal.raoa.elastic.model.RequestAccess;
import ch.bergturbenthal.raoa.elastic.model.User;
import ch.bergturbenthal.raoa.elastic.service.UserManager;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.AsyncService;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.impl.Limiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Slf4j
@Service
public class GitUserManager implements UserManager {
  private static final TreeFilter ALL_USERS_FILTER =
      AndTreeFilter.create(PathFilter.create("users"), PathSuffixFilter.create(".json"));
  private static final TreeFilter ALL_GROUPS_FILTER =
      AndTreeFilter.create(PathFilter.create("groups"), PathSuffixFilter.create(".json"));
  @org.jetbrains.annotations.NotNull private final AlbumList albumList;
  private final Mono<UUID> metaIdMono;
  private final ObjectReader userReader;
  private final ObjectWriter userWriter;
  private final Limiter limiter;
  private final AsyncService asyncService;
  private final ObjectReader groupReader;
  private final ObjectWriter groupWriter;

  public GitUserManager(AlbumList albumList, final Limiter limiter, AsyncService asyncService) {
    this.albumList = albumList;
    this.limiter = limiter;
    this.asyncService = asyncService;
    final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().indentOutput(true).build();

    userReader = objectMapper.readerFor(User.class);
    userWriter = objectMapper.writerFor(User.class);
    groupReader = objectMapper.readerFor(Group.class);
    groupWriter = objectMapper.writerFor(Group.class);

    metaIdMono =
        albumList
            .listAlbums()
            .filterWhen(a -> a.getAccess().getFullPath().map(p -> p.equals(".meta")))
            // .log("meta album")
            .map(AlbumList.FoundAlbum::getAlbumId)
            .singleOrEmpty();
  }

  @Override
  public Mono<User> createNewUser(final RequestAccess foundRequest) {
    return asyncService
        .asyncMono(
            () -> {
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
            })
        .flatMap(Function.identity());
  }

  @Override
  public Mono<Group> createNewGroup(final String groupName) {
    return asyncService
        .asyncMono(
            () -> {
              final Group group =
                  Group.builder()
                      .id(UUID.randomUUID())
                      .name(groupName)
                      .visibleAlbums(Collections.emptySet())
                      .build();
              final String groupFile = createGroupFile(group.getId());
              File tempFile = File.createTempFile(group.getId().toString(), "json");
              groupWriter.writeValue(tempFile, group);
              return overrideFile(groupFile, tempFile, "created group", false)
                  .filter(t -> t)
                  .map(t -> group);
            })
        .flatMap(Function.identity());
  }

  @Override
  public Mono<ObjectId> getMetaVersion() {
    return metaIdMono.flatMap(albumList::getAlbum).flatMap(GitAccess::getCurrentVersion);
  }

  @NotNull
  public Mono<Boolean> overrideFile(
      final String newFilename,
      final File srcData,
      final String commitComment,
      final boolean replaceIfExists) {
    return metaIdMono
        .flatMap(
            albumId ->
                albumList
                    .getAlbum(albumId)
                    .flatMap(GitAccess::createUpdater)
                    .flatMap(
                        u ->
                            u.importFile(srcData.toPath(), newFilename, replaceIfExists)
                                .flatMap(t -> u.commit(commitComment))
                                .filter(t -> t)
                                .doFinally(signal -> u.close())))
        .retryWhen(Retry.backoff(5, Duration.ofMillis(500)))
        .doFinally(signal -> srcData.delete());
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
  public Mono<Boolean> removeGroup(final UUID id) {
    final String userFileName = createGroupFile(id);
    return metaIdMono
        .flatMap(albumList::getAlbum)
        .flatMap(GitAccess::createUpdater)
        .flatMap(
            u -> u.removeFile(userFileName).filter(t -> t).flatMap(t -> u.commit("removed group")))
        .defaultIfEmpty(false);
  }

  @Override
  public void assignNewIdentity(final UUID existingId, final AuthenticationId baseRequest) {
    updateUser(
        existingId,
        user ->
            user.toBuilder()
                .authentications(
                    Stream.concat(user.getAuthentications().stream(), Stream.of(baseRequest))
                        .collect(Collectors.toSet()))
                .build(),
        "added authentication");
  }

  @NotNull
  private synchronized Flux<User> allUsers() {
    return loadUsers(ALL_USERS_FILTER);
  }

  @NotNull
  private Flux<User> loadUsers(final TreeFilter filter) {
    return metaIdMono
        .flatMap(albumList::getAlbum)
        .<User>flatMapMany(
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
                                "load user ")))
        .map(this::cleanupUser);
  }

  private User cleanupUser(final User user) {
    return User.builder()
        .authentications(defaultIfNull(user.getAuthentications(), Collections.emptySet()))
        .groupMembership(defaultIfNull(user.getGroupMembership(), Collections.emptySet()))
        .superuser(user.isSuperuser())
        .userData(user.getUserData())
        .id(user.getId())
        .visibleAlbums(defaultIfNull(user.getVisibleAlbums(), Collections.emptySet()))
        .build();
  }

  @NotNull
  private Flux<Group> loadGroup(final TreeFilter filter) {
    return metaIdMono
        .flatMap(albumList::getAlbum)
        .<Group>flatMapMany(
            a ->
                a.listFiles(filter)
                    .flatMap(
                        e ->
                            limiter.limit(
                                a.readObject(e.getFileId())
                                    .map(
                                        loader -> {
                                          try {
                                            return groupReader.<Group>readValue(loader.getBytes());
                                          } catch (IOException ex) {
                                            throw new RuntimeException(ex);
                                          }
                                        }),
                                "load user ")))
        .map(this::cleanupGroup);
  }

  private Group cleanupGroup(final Group group) {
    return Group.builder()
        .id(group.getId())
        .name(group.getName())
        .visibleAlbums(defaultIfNull(group.getVisibleAlbums(), Collections.emptySet()))
        .labels(defaultIfNull(group.getLabels(), Collections.emptyMap()))
        .build();
  }

  private <T> T defaultIfNull(T value, T defaultValue) {
    if (value != null) return value;
    return defaultValue;
  }

  @NotNull
  private String createUserFile(final UUID id) {
    return "users/" + id.toString() + ".json";
  }

  @NotNull
  private String createGroupFile(final UUID id) {
    return "groups/" + id.toString() + ".json";
  }

  @Override
  public Flux<User> listUsers() {
    return allUsers();
  }

  @Override
  public synchronized Flux<Group> listGroups() {
    return loadGroup(ALL_GROUPS_FILTER);
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
        .singleOrEmpty()
        .map(updater)
        .flatMap(
            updatedUser ->
                asyncService
                    .asyncMono(
                        () -> {
                          final File tempFile = File.createTempFile(userId.toString(), "json");
                          userWriter.writeValue(tempFile, updatedUser);
                          return tempFile;
                        })
                    .flatMap(
                        tempFile ->
                            overrideFile(userFile, tempFile, updateDescription, true)
                                .filter(t -> t)
                                .map(t -> updatedUser)));
  }

  @Override
  public Mono<Group> updateGroup(
      final UUID groupId, final Function<Group, Group> updater, final String updateDescription) {
    final String groupFile = createGroupFile(groupId);
    final PathFilter filter = PathFilter.create(groupFile);
    return loadGroup(filter)
        .singleOrEmpty()
        .map(updater)
        .flatMap(
            updatedGroup ->
                asyncService
                    .asyncMono(
                        () -> {
                          final File tempFile = File.createTempFile(groupFile.toString(), "json");
                          groupWriter.writeValue(tempFile, updatedGroup);
                          return tempFile;
                        })
                    .flatMap(
                        tempFile ->
                            overrideFile(groupFile, tempFile, updateDescription, true)
                                .filter(t -> t)
                                .map(t -> updatedGroup)));
  }
}
