package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.elastic.model.*;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.elastic.service.UserManager;
import ch.bergturbenthal.raoa.libs.model.AlbumMeta;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.FileImporter;
import ch.bergturbenthal.raoa.libs.service.Updater;
import ch.bergturbenthal.raoa.libs.service.UploadFilenameService;
import ch.bergturbenthal.raoa.libs.service.impl.XmpWrapper;
import ch.bergturbenthal.raoa.viewer.interfaces.graphql.model.*;
import ch.bergturbenthal.raoa.viewer.model.graphql.*;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import com.adobe.xmp.XMPMetaFactory;
import com.coxautodev.graphql.tools.GraphQLMutationResolver;
import java.io.File;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.function.TupleUtils;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

@Slf4j
@Component
public class Mutation implements GraphQLMutationResolver {
  private static final Duration TIMEOUT = Duration.ofMinutes(5);
  private final UserManager userManager;
  private final AuthorizationManager authorizationManager;
  private final QueryContextSupplier queryContextSupplier;
  private final DataViewService dataViewService;
  private final AlbumList albumList;
  private final UploadFilenameService uploadFilenameService;
  private final Random random = SecureRandom.getInstanceStrong();

  public Mutation(
      final UserManager userManager,
      final AuthorizationManager authorizationManager,
      final QueryContextSupplier queryContextSupplier,
      final DataViewService dataViewService,
      final AlbumList albumList,
      final UploadFilenameService uploadFilenameService)
      throws NoSuchAlgorithmException {
    this.userManager = userManager;
    this.authorizationManager = authorizationManager;
    this.queryContextSupplier = queryContextSupplier;
    this.dataViewService = dataViewService;
    this.albumList = albumList;
    this.uploadFilenameService = uploadFilenameService;
  }

  @NotNull
  private static Optional<Instant> filterEnd(final Optional<Instant> end) {
    return end.filter(v -> !v.equals(Instant.MAX));
  }

  @NotNull
  private static Optional<Instant> filterBegin(final Optional<Instant> begin) {
    return begin.filter(v -> !v.equals(Instant.MIN));
  }

  @NotNull
  private static Optional<Instant> findBeginOfList(final List<GroupMembership> membershipList) {
    return membershipList.stream()
        .map(membership -> Optional.ofNullable(membership.getFrom()))
        .map(o -> o.orElse(Instant.MIN))
        .min(Comparator.naturalOrder());
  }

  @NotNull
  private static Optional<Instant> findEndOfList(final List<GroupMembership> membershipList) {
    return membershipList.stream()
        .map(membership -> Optional.ofNullable(membership.getUntil()))
        .map(o -> o.orElse(Instant.MAX))
        .max(Comparator.naturalOrder());
  }

  @NotNull
  private static <K, V> BiFunction<K, Function<V, V>, Function<V, V>> mergeFunction(
      final Function<V, V> updateFunction) {
    return (id, existingFunction) -> {
      if (existingFunction == null) return updateFunction;
      return existingFunction.andThen(updateFunction);
    };
  }

  private static Updater.CommitContext createCommitContext(
      final QueryContext queryContext, final String operation) {
    final Updater.CommitContext.CommitContextBuilder builder =
        Updater.CommitContext.builder().message(operation);
    queryContext
        .getCurrentUser()
        .map(User::getUserData)
        .ifPresent(
            user -> {
              builder.username(user.getName());
              builder.email(user.getEmail());
            });
    return builder.build();
  }

  public CompletableFuture<UserReference> createUser(AuthenticationId authenticationId) {
    return queryContextSupplier
        .createContext()
        .filter(QueryContext::canUserManageUsers)
        .flatMap(
            queryContext ->
                dataViewService
                    .getPendingRequest(authenticationId)
                    .flatMap(
                        baseRequest ->
                            userManager.createNewUser(
                                baseRequest, createCommitContext(queryContext, "create user")))
                    .log(
                        "created",
                        Level.INFO,
                        SignalType.REQUEST,
                        SignalType.ON_COMPLETE,
                        SignalType.ON_NEXT,
                        SignalType.ON_ERROR)
                    .flatMap(
                        user ->
                            Flux.merge(
                                    dataViewService
                                        .removePendingAccessRequest(authenticationId)
                                        .log(
                                            "remove pending",
                                            Level.INFO,
                                            SignalType.REQUEST,
                                            SignalType.ON_COMPLETE,
                                            SignalType.ON_NEXT,
                                            SignalType.ON_ERROR),
                                    dataViewService
                                        .updateUserData()
                                        .log(
                                            "update users",
                                            Level.INFO,
                                            SignalType.REQUEST,
                                            SignalType.ON_COMPLETE,
                                            SignalType.ON_NEXT,
                                            SignalType.ON_ERROR))
                                .then(Mono.just(user)))
                    .map(u -> new UserReference(u.getId(), u.getUserData(), queryContext)))
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<GroupReference> createGroup(String name) {
    return queryContextSupplier
        .createContext()
        .filter(QueryContext::canUserManageUsers)
        .flatMap(
            queryContext ->
                userManager
                    .createNewGroup(name, createCommitContext(queryContext, "create group " + name))
                    .map(
                        group -> new GroupReference(group.getId(), queryContext, Mono.just(group))))
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<UpdateResult> updateCredentials(CredentialUpgrade update) {
    return queryContextSupplier
        .createContext()
        .filter(QueryContext::canUserManageUsers)
        .flatMap(
            queryContext -> {
              Map<UUID, Function<User, User>> userMutations = new HashMap<>();
              if (update.getUserUpdates() != null)
                for (SingleUserUpdate userUpdate : update.getUserUpdates()) {
                  userMutations.compute(
                      userUpdate.getUserId(),
                      mergeFunction(
                          userUpdate.isMember()
                              ? (user ->
                                  user.toBuilder()
                                      .visibleAlbums(
                                          Stream.concat(
                                                  user.getVisibleAlbums().stream(),
                                                  Stream.of(userUpdate.getAlbumId()))
                                              .collect(Collectors.toSet()))
                                      .build())
                              : (user ->
                                  user.toBuilder()
                                      .visibleAlbums(
                                          user.getVisibleAlbums().stream()
                                              .filter(aid -> !userUpdate.getAlbumId().equals(aid))
                                              .collect(Collectors.toSet()))
                                      .build())));
                }
              if (update.getGroupMembershipUpdates() != null)
                for (SingleGroupMembershipUpdate groupMembershipUpdate :
                    update.getGroupMembershipUpdates()) {
                  final Function<User, User> updateFunction;
                  final UUID groupId = groupMembershipUpdate.getGroupId();
                  updateFunction =
                      groupMembershipUpdate.isMember()
                          ? (user -> {
                            final GroupMembership newMembership =
                                GroupMembership.builder()
                                    .group(groupId)
                                    .from(groupMembershipUpdate.getFrom())
                                    .until(groupMembershipUpdate.getUntil())
                                    .build();
                            Map<UUID, List<GroupMembership>> openMemberships = new HashMap<>();
                            Set<GroupMembership> resultingMemberships = new HashSet<>();
                            Stream.concat(
                                    user.getGroupMembership().stream(), Stream.of(newMembership))
                                .sorted(
                                    Comparator.comparing(
                                        m -> Optional.ofNullable(m.getFrom()).orElse(Instant.MIN)))
                                .forEach(
                                    groupMembership -> {
                                      final UUID group = groupMembership.getGroup();
                                      final List<GroupMembership> membershipList =
                                          openMemberships.computeIfAbsent(
                                              group, k -> new ArrayList<>());
                                      final Optional<Instant> endOfList =
                                          findEndOfList(membershipList);
                                      final Optional<Instant> beginOfList =
                                          findBeginOfList(membershipList);
                                      final Instant entryStart =
                                          Optional.ofNullable(groupMembership.getFrom())
                                              .orElse(Instant.MIN);
                                      if (endOfList.isPresent() && beginOfList.isPresent()) {
                                        Instant windowStart = beginOfList.get();
                                        Instant windowEnd = endOfList.get();
                                        if (entryStart.isAfter(windowEnd)) {
                                          resultingMemberships.add(
                                              GroupMembership.builder()
                                                  .group(group)
                                                  .from(
                                                      filterBegin(Optional.of(windowStart))
                                                          .orElse(null))
                                                  .until(
                                                      filterEnd(Optional.of(windowEnd))
                                                          .orElse(null))
                                                  .build());
                                          membershipList.clear();
                                        } else {
                                          membershipList.add(groupMembership);
                                        }
                                      } else membershipList.add(groupMembership);
                                    });
                            openMemberships.forEach(
                                (group, membershipList) -> {
                                  if (membershipList.isEmpty()) return;
                                  final Optional<Instant> beginOfList =
                                      filterBegin(findBeginOfList(membershipList));
                                  final Optional<Instant> endOfList =
                                      filterEnd(findEndOfList(membershipList));
                                  resultingMemberships.add(
                                      GroupMembership.builder()
                                          .group(group)
                                          .from(beginOfList.orElse(null))
                                          .until(endOfList.orElse(null))
                                          .build());
                                });

                            return user.toBuilder().groupMembership(resultingMemberships).build();
                          })
                          : (user -> {
                            final Instant timeWindowBegin =
                                Optional.ofNullable(groupMembershipUpdate.getFrom())
                                    .orElse(Instant.MIN);
                            final Instant timeWindowEnd =
                                Optional.ofNullable(groupMembershipUpdate.getUntil())
                                    .orElse(Instant.MAX);
                            return user.toBuilder()
                                .groupMembership(
                                    user.getGroupMembership().stream()
                                        .flatMap(
                                            existingMembership -> {
                                              if (!existingMembership.getGroup().equals(groupId))
                                                return Stream.of(existingMembership);
                                              final Instant membershipBegin =
                                                  Optional.ofNullable(existingMembership.getFrom())
                                                      .orElse(Instant.MIN);
                                              final Instant membershipEnd =
                                                  Optional.ofNullable(existingMembership.getUntil())
                                                      .orElse(Instant.MAX);
                                              if (membershipEnd.isBefore(timeWindowBegin)
                                                  || membershipBegin.isAfter(timeWindowEnd))
                                                return Stream.of(existingMembership);
                                              final Stream.Builder<GroupMembership>
                                                  remainingSlicesBuilder = Stream.builder();
                                              if (membershipBegin.isBefore(timeWindowBegin)) {
                                                remainingSlicesBuilder.add(
                                                    GroupMembership.builder()
                                                        .group(groupId)
                                                        .from(
                                                            filterBegin(
                                                                    Optional.of(membershipBegin))
                                                                .orElse(null))
                                                        .until(
                                                            filterEnd(Optional.of(timeWindowBegin))
                                                                .orElse(null))
                                                        .build());
                                              }
                                              if (membershipEnd.isAfter(timeWindowEnd)) {
                                                remainingSlicesBuilder.add(
                                                    GroupMembership.builder()
                                                        .group(groupId)
                                                        .from(
                                                            filterBegin(Optional.of(timeWindowEnd))
                                                                .orElse(null))
                                                        .until(
                                                            filterEnd(Optional.of(membershipEnd))
                                                                .orElse(null))
                                                        .build());
                                              }
                                              return remainingSlicesBuilder.build();
                                            })
                                        .collect(Collectors.toSet()))
                                .build();
                          });
                  userMutations.compute(
                      groupMembershipUpdate.getUserId(), mergeFunction(updateFunction));
                }
              Map<UUID, Function<Group, Group>> groupMutations = new HashMap<>();
              if (update.getUserUpdates() != null)
                for (SingleGroupUpdate groupUpdate : update.getGroupUpdates()) {
                  groupMutations.compute(
                      groupUpdate.getGroupId(),
                      mergeFunction(
                          groupUpdate.isMember()
                              ? (group ->
                                  group
                                      .toBuilder()
                                      .visibleAlbums(
                                          Stream.concat(
                                                  group.getVisibleAlbums().stream(),
                                                  Stream.of(groupUpdate.getAlbumId()))
                                              .collect(Collectors.toSet()))
                                      .build())
                              : (group ->
                                  group
                                      .toBuilder()
                                      .visibleAlbums(
                                          group.getVisibleAlbums().stream()
                                              .filter(aid -> groupUpdate.getAlbumId().equals(aid))
                                              .collect(Collectors.toSet()))
                                      .build())));
                }
              return Flux.merge(
                      Flux.fromIterable(userMutations.entrySet())
                          .flatMap(
                              mutEntry ->
                                  userManager
                                      .context(
                                          mutEntry.getKey(),
                                          mutEntry.getValue(),
                                          createCommitContext(
                                              queryContext, "update user " + mutEntry.getKey()))
                                      .single(),
                              1)
                          .count(),
                      Flux.fromIterable(groupMutations.entrySet())
                          .flatMap(
                              mutEntry ->
                                  userManager.updateGroup(
                                      mutEntry.getKey(),
                                      mutEntry.getValue(),
                                      createCommitContext(
                                          queryContext, "update group " + mutEntry.getKey())),
                              1)
                          .count())
                  .count()
                  .flatMap(c -> dataViewService.updateUserData().thenReturn(true))
                  .onErrorResume(
                      ex -> {
                        log.warn("Cannot update", ex);
                        return Mono.just(false);
                      });
            })
        .map(UpdateResult::new)
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<Boolean> removeUser(UUID userId) {
    return queryContextSupplier
        .createContext()
        .flatMap(
            queryContext ->
                queryContext.canUserManageUsers()
                    ? userManager
                        .removeUser(userId, createCommitContext(queryContext, "remove user"))
                        .flatMap(r -> dataViewService.updateUserData().thenReturn(r))
                        .defaultIfEmpty(false)
                    : Mono.empty())
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<? extends RequestAccessResult> requestAccess(String comment) {
    return queryContextSupplier
        .createContext()
        .flatMap(
            queryContext ->
                queryContext.getAuthenticationState().map(s -> Tuples.of(queryContext, s)))
        .flatMap(
            TupleUtils.function(
                (queryContext, authenticationState) -> {
                  final Optional<AuthenticationId> authenticationId =
                      queryContext.currentAuthenticationId();
                  if (authenticationState == AuthenticationState.AUTHENTICATED
                      && authenticationId.isPresent()) {
                    final PersonalUserData personalUserData =
                        authorizationManager.readPersonalUserData(
                            queryContext.getSecurityContext());
                    final RequestAccess.RequestAccessBuilder builder = RequestAccess.builder();
                    builder.authenticationId(authenticationId.get());
                    builder.comment(comment);
                    builder.requestTime(Instant.now());

                    builder.userData(personalUserData);
                    return dataViewService
                        .requestAccess(builder.build())
                        .map(c -> RequestAccessResultCode.OK);
                  }
                  return Mono.just(
                      authenticationState == AuthenticationState.AUTHORIZED
                          ? RequestAccessResultCode.ALREADY_ACCEPTED
                          : RequestAccessResultCode.NOT_LOGGED_IN);
                }))
        .map(
            code ->
                new RequestAccessResult() {
                  @Override
                  public boolean isOk() {
                    return code.isOk();
                  }

                  @Override
                  public RequestAccessResultCode getResult() {
                    return code;
                  }
                })
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<Boolean> removeRequest(AuthenticationId id) {
    return dataViewService.removePendingAccessRequest(id).thenReturn(true).toFuture();
  }

  public CompletableFuture<UserReference> setCanManageUserFlag(UUID userId, boolean enabled) {
    return queryContextSupplier
        .createContext()
        .filter(QueryContext::canUserManageUsers)
        .flatMap(
            queryContext -> {
              return userManager
                  .context(
                      userId,
                      user -> user.toBuilder().superuser(enabled).build(),
                      createCommitContext(
                          queryContext,
                          queryContext.getCurrentUser().orElseThrow().getUserData().getName()
                              + " setCanManageUser to "
                              + enabled))
                  .flatMap(user -> dataViewService.updateUserData().thenReturn(user))
                  .map(u -> new UserReference(u.getId(), u.getUserData(), queryContext));
            })
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<UserReference> setAlbumVisibility(
      UUID userId, UUID albumId, boolean enabled) {
    return queryContextSupplier
        .createContext()
        .filter(QueryContext::canUserManageUsers)
        .flatMap(
            queryContext ->
                userManager
                    .context(
                        userId,
                        user -> {
                          final Set<UUID> visibleAlbums = new HashSet<>(user.getVisibleAlbums());
                          if (enabled) visibleAlbums.add(albumId);
                          else visibleAlbums.remove(albumId);
                          return user.toBuilder().visibleAlbums(visibleAlbums).build();
                        },
                        createCommitContext(
                            queryContext,
                            queryContext.getCurrentUser().orElseThrow().getUserData().getName()
                                + (enabled ? " shows user " : " hides for user")
                                + userId
                                + " album "
                                + albumId))
                    .flatMap(user -> dataViewService.updateUserData().thenReturn(user))
                    .map(u -> new UserReference(u.getId(), u.getUserData(), queryContext)))
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<UserReference> updateUser(UUID userId, UserUpdate update) {
    log.info("update: " + update);

    return queryContextSupplier
        .createContext()
        .filter(QueryContext::canUserManageUsers)
        .flatMap(
            queryContext ->
                userManager
                    .context(
                        userId,
                        user -> {
                          final User.UserBuilder userBuilder = user.toBuilder();
                          if (update.getVisibilityUpdates() != null) {
                            final Set<UUID> visibleAlbums = new HashSet<>(user.getVisibleAlbums());
                            for (UserVisibilityUpdate visibilityUpdate :
                                update.getVisibilityUpdates()) {
                              if (visibilityUpdate.isVisibility()) {
                                visibleAlbums.add(visibilityUpdate.getAlbumId());
                              } else {
                                visibleAlbums.remove(visibilityUpdate.getAlbumId());
                              }
                            }
                            userBuilder.visibleAlbums(visibleAlbums);
                          }
                          if (update.getCanManageUsers() != null) {
                            userBuilder.superuser(update.getCanManageUsers());
                          }
                          if (update.getIsEditor() != null) {
                            userBuilder.editor(update.getIsEditor());
                          }
                          return userBuilder.build();
                        },
                        createCommitContext(
                            queryContext,
                            queryContext.getCurrentUser().orElseThrow().getUserData().getName()
                                + " updates "
                                + userId))
                    .flatMap(user -> dataViewService.updateUserData().thenReturn(user))
                    .map(u -> new UserReference(u.getId(), u.getUserData(), queryContext)))
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<GroupReference> updateGroup(UUID groupId, GroupUpdate update) {

    return queryContextSupplier
        .createContext()
        .filter(QueryContext::canUserManageUsers)
        .flatMap(
            queryContext ->
                userManager
                    .updateGroup(
                        groupId,
                        group -> {
                          final Group.GroupBuilder builder = group.toBuilder();
                          Optional.ofNullable(update.getNewName())
                              .map(String::trim)
                              .filter(v -> !v.isEmpty())
                              .ifPresent(builder::name);
                          final HashMap<String, String> labels =
                              new HashMap<>(
                                  Objects.requireNonNullElse(
                                      group.getLabels(), Collections.emptyMap()));
                          Optional.ofNullable(update.getRemoveLabels())
                              .ifPresent(rem -> labels.keySet().removeAll(rem));
                          Optional.ofNullable(update.getNewLabels()).stream()
                              .flatMap(Collection::stream)
                              .forEach(lv -> labels.put(lv.getLabelName(), lv.getLabelValue()));
                          builder.labels(labels);
                          return builder.build();
                        },
                        createCommitContext(
                            queryContext,
                            queryContext.getCurrentUser().orElseThrow().getUserData().getName()
                                + " updates "
                                + groupId))
                    .flatMap(user -> dataViewService.updateUserData().thenReturn(user.getId()))
                    .map(
                        u ->
                            new GroupReference(
                                u, queryContext, dataViewService.findGroupById(u).cache())))
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<AlbumEntry> updateAlbumEntry(
      UUID albumId, String albumEntryId, AlbumEntryUpdate update) {
    final ObjectId entryId = ObjectId.fromString(albumEntryId);
    return queryContextSupplier
        .createContext()
        .filterWhen(
            context ->
                authorizationManager.canUserModifyAlbum(context.getSecurityContext(), albumId))
        .flatMap(
            queryContext ->
                albumList
                    .getAlbum(albumId)
                    .flatMap(
                        ga -> {
                          final Updater.CommitContext context =
                              createCommitContext(queryContext, "updateAlbumEntry");
                          return ga.filenameOfObject(entryId)
                              .map(filename -> filename + ".xmp")
                              .flatMap(
                                  filename ->
                                      ga.readObject(filename)
                                          .flatMap(ga::readXmpMeta)
                                          .switchIfEmpty(
                                              Mono.defer(() -> Mono.just(XMPMetaFactory.create())))
                                          .map(
                                              xmpMeta -> {
                                                final XmpWrapper xmpWrapper =
                                                    new XmpWrapper(xmpMeta);
                                                update
                                                    .getAddKeywords()
                                                    .forEach(xmpWrapper::addKeyword);
                                                update
                                                    .getRemoveKeywords()
                                                    .forEach(xmpWrapper::removeKeyword);
                                                return xmpMeta;
                                              })
                                          .flatMap(
                                              xmpMeta ->
                                                  ga.writeXmpMeta(filename, xmpMeta, context)
                                                      .map(ok -> xmpMeta)))
                              .flatMap(
                                  xmpMeta ->
                                      dataViewService
                                          .updateKeyword(albumId, entryId, xmpMeta)
                                          .map(result -> xmpMeta));
                        })
                    .flatMap(xmpMeta -> dataViewService.updateKeyword(albumId, entryId, xmpMeta))
                    .map(
                        e ->
                            new AlbumEntry(
                                new Album(
                                    albumId,
                                    queryContext,
                                    dataViewService.readAlbum(albumId).cache()),
                                e.getEntryId().name(),
                                e)))
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<Album> updateAlbum(UUID albumId, AlbumUpdate update) {
    return queryContextSupplier
        .createContext()
        .filter(QueryContext::canUserManageUsers)
        .flatMap(
            queryContext -> {
              final Updater.CommitContext context =
                  createCommitContext(queryContext, "updateAlbum");
              return albumList
                  .getAlbum(albumId)
                  .flatMap(
                      ga ->
                          ga.updateMetadata(
                                  albumMeta -> {
                                    final AlbumMeta.AlbumMetaBuilder builder =
                                        albumMeta.toBuilder();
                                    Optional.ofNullable(update.getNewAlbumTitle())
                                        .map(String::trim)
                                        .filter(v -> !v.isEmpty())
                                        .ifPresent(builder::albumTitle);
                                    Optional.ofNullable(update.getNewTitleEntry())
                                        .ifPresent(builder::titleEntry);
                                    final HashMap<String, String> labels =
                                        new HashMap<>(
                                            Objects.requireNonNullElse(
                                                albumMeta.getLabels(), Collections.emptyMap()));
                                    Optional.ofNullable(update.getRemoveLabels())
                                        .ifPresent(rem -> labels.keySet().removeAll(rem));
                                    Optional.ofNullable(update.getNewLabels()).stream()
                                        .flatMap(Collection::stream)
                                        .forEach(
                                            lv ->
                                                labels.put(lv.getLabelName(), lv.getLabelValue()));
                                    builder.labels(labels);
                                    return builder.build();
                                  },
                                  context)
                              .map(ok -> ga))
                  .flatMap(
                      ga -> {
                        if (update.getAutoadd() != null)
                          return ga.updateAutoadd(update.getAutoadd(), context);
                        return Mono.just(true);
                      })
                  .map(
                      c ->
                          new Album(
                              albumId, queryContext, dataViewService.readAlbum(albumId).cache()));
            })
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<List<ImportedFile>> commitImport(List<ImportFile> files) {

    return queryContextSupplier
        .createContext()
        .filter(QueryContext::canUserEditData)
        .flatMap(
            context -> {
              Function<UUID, Mono<Boolean>> authorizer =
                  id -> authorizationManager.canUserModifyAlbum(context.getSecurityContext(), id);
              final FileImporter importer =
                  albumList.createImporter(createCommitContext(context, "import files"));
              return Flux.fromIterable(
                      () ->
                          files.stream()
                              .map(
                                  file -> {
                                    final File uploadFile =
                                        uploadFilenameService.createTempUploadFile(
                                            file.getFileId());
                                    if (uploadFile.exists()
                                        && uploadFile.length() == file.getSize())
                                      return Optional.of(Tuples.of(file, uploadFile));
                                    return Optional.<Tuple2<ImportFile, File>>empty();
                                  })
                              .filter(Optional::isPresent)
                              .map(Optional::get)
                              .iterator())
                  .flatMap(
                      t -> {
                        final File tempFile = t.getT2();
                        final String filename = t.getT1().getFilename();
                        final Mono<Tuple2<UUID, ObjectId>> importResult =
                            importer.importFile(tempFile.toPath(), filename, authorizer);
                        return importResult.map(
                            t2 -> Tuples.of(t2.getT1(), t2.getT2(), t.getT1().getFileId()));
                      },
                      5)
                  .collectList()
                  .flatMap(fileList -> importer.commitAll().map(done -> fileList))
                  .flatMap(
                      list ->
                          dataViewService
                              .updateAlbums(
                                  Flux.fromIterable(list)
                                      .map(Tuple2::getT1)
                                      .distinct()
                                      .flatMap(
                                          it ->
                                              albumList
                                                  .getAlbum(it)
                                                  .map(ga -> new AlbumList.FoundAlbum(it, ga)),
                                          10))
                              .thenReturn(list))
                  .flatMapIterable(Function.identity())
                  .flatMap(
                      t -> {
                        final UUID fileId = t.getT3();
                        final UUID albumId = t.getT1();
                        final ObjectId entryId = t.getT2();
                        return dataViewService
                            .loadEntry(albumId, entryId)
                            .map(
                                entryData ->
                                    new AlbumEntry(
                                        new Album(
                                            albumId,
                                            context,
                                            dataViewService.readAlbum(albumId).cache()),
                                        entryId.name(),
                                        entryData))
                            .map(entry -> new ImportedFile(fileId, entry));
                      },
                      20)
                  .collectList();
            })
        .doOnError(ex -> log.warn("Cannot commit import", ex))
        .toFuture();
  }

  public CompletableFuture<Album> createAlbum(List<String> path) {
    return queryContextSupplier
        .createContext()
        .filter(QueryContext::canUserManageUsers)
        .flatMap(
            queryContext ->
                albumList
                    .createAlbum(path)
                    .map(
                        albumId ->
                            new Album(
                                albumId, queryContext, dataViewService.readAlbum(albumId).cache())))
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<TemporaryPassword> createTemporaryPassword(Integer duration) {

    final Duration passwordTimeout =
        Optional.ofNullable(duration)
            .filter(d -> d > 0)
            .filter(d -> d < 7200)
            .map(Duration::ofSeconds)
            .orElse(Duration.ofMinutes(5));
    return queryContextSupplier
        .createContext()
        .filter(c -> c.getCurrentUser().isPresent())
        .flatMap(
            context ->
                context
                    .getCurrentUser()
                    .map(
                        user -> {
                          final UUID userId = user.getId();

                          String password =
                              random
                                  .ints(0, 62)
                                  .mapToObj(
                                      i -> {
                                        if (i < 26) {
                                          return (char) ('A' + i);
                                        }
                                        if (i < 52) {
                                          return (char) ('a' + i - 26);
                                        }
                                        return (char) ('0' + i - 52);
                                      })
                                  .limit(40)
                                  .collect(
                                      StringBuilder::new,
                                      StringBuilder::append,
                                      StringBuilder::append)
                                  .toString();
                          return dataViewService.createTemporaryPassword(
                              userId, password, Instant.now().plus(passwordTimeout));
                        })
                    .orElse(Mono.empty()))
        .timeout(TIMEOUT)
        .toFuture();
  }
}
