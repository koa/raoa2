package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.viewer.model.graphql.*;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AccessRequest;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AuthenticationId;
import ch.bergturbenthal.raoa.viewer.model.usermanager.PersonalUserData;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import ch.bergturbenthal.raoa.viewer.service.DataViewService;
import ch.bergturbenthal.raoa.viewer.service.UserManager;
import com.coxautodev.graphql.tools.GraphQLMutationResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.function.TupleUtils;
import reactor.util.function.Tuples;

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
                    final AccessRequest.AccessRequestBuilder builder = AccessRequest.builder();
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
}
