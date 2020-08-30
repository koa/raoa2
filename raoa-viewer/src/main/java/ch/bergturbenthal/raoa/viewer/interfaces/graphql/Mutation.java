package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.elastic.model.*;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.elastic.service.UserManager;
import ch.bergturbenthal.raoa.viewer.interfaces.graphql.model.*;
import ch.bergturbenthal.raoa.viewer.model.graphql.*;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import com.coxautodev.graphql.tools.GraphQLMutationResolver;
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
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.function.TupleUtils;
import reactor.util.function.Tuples;

@Slf4j
@Component
public class Mutation implements GraphQLMutationResolver {
  private static final Duration TIMEOUT = Duration.ofMinutes(5);
  private final UserManager userManager;
  private final AuthorizationManager authorizationManager;
  private final QueryContextSupplier queryContextSupplier;
  private final DataViewService dataViewService;

  public Mutation(
      final UserManager userManager,
      final AuthorizationManager authorizationManager,
      final QueryContextSupplier queryContextSupplier,
      final DataViewService dataViewService) {
    this.userManager = userManager;
    this.authorizationManager = authorizationManager;
    this.queryContextSupplier = queryContextSupplier;
    this.dataViewService = dataViewService;
  }

  public CompletableFuture<UserReference> createUser(AuthenticationId authenticationId) {
    return queryContextSupplier
        .createContext()
        .filter(QueryContext::canUserManageUsers)
        .flatMap(
            queryContext ->
                dataViewService
                    .getPendingRequest(authenticationId)
                    .flatMap(userManager::createNewUser)
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
                    .createNewGroup(name)
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
              for (SingleGroupMembershipUpdate groupMembershipUpdate :
                  update.getGroupMembershipUpdates()) {
                userMutations.compute(
                    groupMembershipUpdate.getUserId(),
                    mergeFunction(
                        groupMembershipUpdate.isMember()
                            ? (user ->
                                user.toBuilder()
                                    .groupMembership(
                                        Stream.concat(
                                                user.getGroupMembership().stream(),
                                                Stream.of(groupMembershipUpdate.getGroupId()))
                                            .collect(Collectors.toSet()))
                                    .build())
                            : (user ->
                                user.toBuilder()
                                    .groupMembership(
                                        user.getGroupMembership().stream()
                                            .filter(
                                                gid ->
                                                    !groupMembershipUpdate.getGroupId().equals(gid))
                                            .collect(Collectors.toSet()))
                                    .build())));
              }
              Map<UUID, Function<Group, Group>> groupMutations = new HashMap<>();
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
                                      .updateUser(
                                          mutEntry.getKey(),
                                          mutEntry.getValue(),
                                          "update user " + mutEntry.getKey())
                                      .single())
                          .count(),
                      Flux.fromIterable(groupMutations.entrySet())
                          .flatMap(
                              mutEntry ->
                                  userManager
                                      .updateGroup(
                                          mutEntry.getKey(),
                                          mutEntry.getValue(),
                                          "update group " + mutEntry.getKey())
                                      .single())
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

  @NotNull
  public <K, V> BiFunction<K, Function<V, V>, Function<V, V>> mergeFunction(
      final Function<V, V> updateFunction) {
    return (id, existingFunction) -> {
      if (existingFunction == null) return updateFunction;
      return existingFunction.andThen(updateFunction);
    };
  }

  public CompletableFuture<Boolean> removeUser(UUID userId) {
    return queryContextSupplier
        .createContext()
        .flatMap(
            queryContext ->
                queryContext.canUserManageUsers()
                    ? userManager
                        .removeUser(userId)
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
                  .updateUser(
                      userId,
                      user -> user.toBuilder().superuser(enabled).build(),
                      queryContext.getCurrentUser().orElseThrow().getUserData().getName()
                          + " setCanManageUser to "
                          + enabled)
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
                    .updateUser(
                        userId,
                        user -> {
                          final Set<UUID> visibleAlbums = new HashSet<>(user.getVisibleAlbums());
                          if (enabled) visibleAlbums.add(albumId);
                          else visibleAlbums.remove(albumId);
                          return user.toBuilder().visibleAlbums(visibleAlbums).build();
                        },
                        queryContext.getCurrentUser().orElseThrow().getUserData().getName()
                            + (enabled ? " shows user " : " hides for user")
                            + userId
                            + " album "
                            + albumId)
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
                    .updateUser(
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
                          return userBuilder.build();
                        },
                        queryContext.getCurrentUser().orElseThrow().getUserData().getName()
                            + " updates "
                            + userId)
                    .flatMap(user -> dataViewService.updateUserData().thenReturn(user))
                    .map(u -> new UserReference(u.getId(), u.getUserData(), queryContext)))
        .timeout(TIMEOUT)
        .toFuture();
  }
}
