package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.elastic.model.AlbumData;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.viewer.model.graphql.*;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import com.coxautodev.graphql.tools.GraphQLQueryResolver;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class Query implements GraphQLQueryResolver {
  private static final Duration TIMEOUT = Duration.ofMinutes(5);
  private final QueryContextSupplier queryContextSupplier;
  private final DataViewService dataViewService;
  private final AuthorizationManager authorizationManager;

  public Query(
      final QueryContextSupplier queryContextSupplier,
      final DataViewService dataViewService,
      final AuthorizationManager authorizationManager) {
    this.queryContextSupplier = queryContextSupplier;
    this.dataViewService = dataViewService;
    this.authorizationManager = authorizationManager;
  }

  public CompletableFuture<Album> getAlbumById(UUID albumId) {
    return queryContextSupplier
        .createContext()
        .filterWhen(
            queryContext ->
                authorizationManager.canUserAccessToAlbum(
                    queryContext.getSecurityContext(), albumId))
        .map(c -> new Album(albumId, c, dataViewService.readAlbum(albumId).cache()))
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<List<RegistrationRequest>> listPendingRequests() {
    return queryContextSupplier
        .createContext()
        .filter(QueryContext::canUserManageUsers)
        .flatMapMany(
            context ->
                dataViewService
                    .listAllRequestedAccess()
                    .map(
                        r -> {
                          final RegistrationRequest.RegistrationRequestBuilder builder =
                              RegistrationRequest.builder()
                                  .authenticationId(r.getAuthenticationId())
                                  .data(r.getUserData())
                                  .reason(r.getComment());
                          if (r.getRequestedAlbum() != null) {
                            builder.requestAlbum(
                                new Album(
                                    r.getRequestedAlbum(),
                                    context,
                                    dataViewService.readAlbum(r.getRequestedAlbum()).cache()));
                          }
                          return builder.build();
                        }))
        .collectList()
        // .log("pending request")
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<List<Album>> listAlbums() {
    return queryContextSupplier
        .createContext()
        .flatMap(
            queryContext ->
                dataViewService
                    .listAlbums()
                    // .log("album")
                    .map(AlbumData::getRepositoryId)
                    .filterWhen(
                        id ->
                            authorizationManager.canUserAccessToAlbum(
                                queryContext.getSecurityContext(), id))
                    .map(
                        albumId ->
                            new Album(
                                albumId, queryContext, dataViewService.readAlbum(albumId).cache()))
                    .collectList())
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<List<UserReference>> listUsers() {
    return queryContextSupplier
        .createContext()
        .filter(QueryContext::canUserManageUsers)
        .flatMap(
            queryContext ->
                dataViewService
                    .listUsers()
                    .map(u -> new UserReference(u.getId(), u.getUserData(), queryContext))
                    .collectList())
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<UserReference> userById(UUID userid) {
    return queryContextSupplier
        .createContext()
        .filter(QueryContext::canUserManageUsers)
        .flatMap(
            queryContext ->
                dataViewService
                    .findUserById(userid)
                    .map(u -> new UserReference(u.getId(), u.getUserData(), queryContext)))
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<AuthenticationState> authenticationState() {
    // log.info("Query for authentication state");
    return queryContextSupplier
        .createContext()
        .flatMap(QueryContext::getAuthenticationState)
        .timeout(TIMEOUT)
        .defaultIfEmpty(AuthenticationState.UNKNOWN)
        .toFuture();
  }

  public CompletableFuture<UserReference> currentUser() {
    return queryContextSupplier
        .createContext()
        .map(u -> u.getCurrentUser().map(c -> new UserReference(c.getId(), c.getUserData(), u)))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<List<GroupReference>> listGroups() {
    return queryContextSupplier
        .createContext()
        .flatMapMany(
            context ->
                dataViewService
                    .listGroups()
                    //            .log("group")
                    .filter(group -> context.canAccessGroup(group.getId()))
                    .map(g -> new GroupReference(g.getId(), context, Mono.just(g)))
            //          .log("group-ref")
            )
        // .log("result")
        .collectList()
        .timeout(TIMEOUT)
        .toFuture();
  }
}
