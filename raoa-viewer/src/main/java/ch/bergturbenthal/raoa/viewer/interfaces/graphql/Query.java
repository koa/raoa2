package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.viewer.model.graphql.Album;
import ch.bergturbenthal.raoa.viewer.model.graphql.AuthenticationInformation;
import ch.bergturbenthal.raoa.viewer.model.graphql.AuthenticationState;
import ch.bergturbenthal.raoa.viewer.model.usermanager.PersonalUserData;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import com.coxautodev.graphql.tools.GraphQLQueryResolver;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Component
public class Query implements GraphQLQueryResolver {
  private final AlbumList albumList;
  private final AuthorizationManager authorizationManager;

  public Query(final AlbumList albumList, final AuthorizationManager authorizationManager) {
    this.albumList = albumList;
    this.authorizationManager = authorizationManager;
  }

  public Album getAlbumById(UUID albumId) {
    return new Album(albumId);
  }

  public CompletableFuture<List<Album>> listAlbums() {
    final SecurityContext context = SecurityContextHolder.getContext();

    return authorizationManager
        .currentUser(context)
        .flatMapMany(
            user ->
                albumList
                    .listAlbums()
                    .map(AlbumList.FoundAlbum::getAlbumId)
                    .filter(id -> user.isSuperuser() || user.getVisibleAlbums().contains(id))
                    .map(this::getAlbumById))
        .collectList()
        .toFuture();
  }

  public CompletableFuture<AuthenticationInformation> authenticationState() {
    log.info("Query for authentication state");

    final SecurityContext context = SecurityContextHolder.getContext();
    final boolean userAuthenticated = authorizationManager.isUserAuthenticated(context);
    if (userAuthenticated) {
      final PersonalUserData personalUserData = authorizationManager.readPersonalUserData(context);
      return authorizationManager
          .currentUser(context)
          // .log("existing user")
          .map(
              storedUser -> {
                if (!storedUser
                    .getUserData()
                    .toBuilder()
                    .comment("")
                    .build()
                    .equals(personalUserData)) {
                  // TODO update user data
                }

                return AuthenticationState.AUTHORIZED;
              })
          .switchIfEmpty(
              Mono.defer(
                  () ->
                      Mono.just(
                          authorizationManager.hasPendingRequest(context)
                              ? AuthenticationState.AUTHORIZATION_REQUESTED
                              : AuthenticationState.AUTHENTICATED)))
          .map(
              authorized -> {
                final AuthenticationInformation.AuthenticationInformationBuilder builder =
                    AuthenticationInformation.builder();

                builder.state(authorized);
                builder.name(personalUserData.getName());
                builder.email(personalUserData.getEmail());
                builder.picture(personalUserData.getPicture());
                return builder.build();
              })
          // .log("response state")
          .subscribeOn(Schedulers.elastic())
          .toFuture();
    } else
      return Mono.just(
              AuthenticationInformation.builder().state(AuthenticationState.UNKNOWN).build())
          .toFuture();
  }
}
