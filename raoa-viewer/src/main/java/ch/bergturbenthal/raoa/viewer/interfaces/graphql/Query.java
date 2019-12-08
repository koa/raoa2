package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.libs.model.elasticsearch.AlbumData;
import ch.bergturbenthal.raoa.libs.service.DataViewService;
import ch.bergturbenthal.raoa.viewer.model.graphql.*;
import com.coxautodev.graphql.tools.GraphQLQueryResolver;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class Query implements GraphQLQueryResolver {
  private static final Duration TIMEOUT = Duration.ofMinutes(5);
  private final QueryContextSupplier queryContextSupplier;
  private final DataViewService dataViewService;

  public Query(
      final QueryContextSupplier queryContextSupplier, final DataViewService dataViewService) {
    this.queryContextSupplier = queryContextSupplier;
    this.dataViewService = dataViewService;
  }

  public CompletableFuture<Album> getAlbumById(UUID albumId) {
    return queryContextSupplier
        .createContext()
        .filter(q -> q.canAccessAlbum(albumId))
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
                    .filter(queryContext::canAccessAlbum)
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

  public CompletableFuture<AuthenticationState> authenticationState() {
    // log.info("Query for authentication state");
    return queryContextSupplier
        .createContext()
        .flatMap(QueryContext::getAuthenticationState)
        .timeout(TIMEOUT)
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
}
