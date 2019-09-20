package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.viewer.model.graphql.*;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import ch.bergturbenthal.raoa.viewer.service.UserManager;
import com.coxautodev.graphql.tools.GraphQLQueryResolver;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class Query implements GraphQLQueryResolver {
  private static final Duration TIMEOUT = Duration.ofMinutes(5);
  private final AlbumList albumList;
  private final AuthorizationManager authorizationManager;
  private final UserManager userManager;
  private final QueryContextSupplier queryContextSupplier;

  public Query(
      final AlbumList albumList,
      final AuthorizationManager authorizationManager,
      final UserManager userManager,
      final QueryContextSupplier queryContextSupplier) {
    this.albumList = albumList;
    this.authorizationManager = authorizationManager;
    this.userManager = userManager;
    this.queryContextSupplier = queryContextSupplier;
  }

  public CompletableFuture<Album> getAlbumById(UUID albumId) {
    return queryContextSupplier
        .createContext()
        .map(c -> new Album(albumId, c))
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<List<RegistrationRequest>> listPendingRequests() {
    return queryContextSupplier
        .createContext()
        .map(
            context -> {
              if (context.canUserManageUsers())
                return userManager.listPendingRequests().stream()
                    .map(
                        r ->
                            RegistrationRequest.builder()
                                .authenticationId(r.getAuthenticationId())
                                .data(r.getUserData())
                                .reason(r.getComment())
                                .requestAlbum(new Album(r.getRequestedAlbum(), context))
                                .build())
                    .collect(Collectors.toList());
              else return Collections.<RegistrationRequest>emptyList();
            })
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<List<Album>> listAlbums() {
    return queryContextSupplier
        .createContext()
        .flatMap(
            queryContext ->
                albumList
                    .listAlbums()
                    .map(AlbumList.FoundAlbum::getAlbumId)
                    .filter(queryContext::canAccessAlbum)
                    .map(albumId -> new Album(albumId, queryContext))
                    .collectList())
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<AuthenticationState> authenticationState() {
    // log.info("Query for authentication state");
    return queryContextSupplier
        .createContext()
        .map(QueryContext::getAuthenticationState)
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
