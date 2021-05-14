package ch.bergturbenthal.raoa.viewer.service;

import ch.bergturbenthal.raoa.elastic.model.AlbumData;
import ch.bergturbenthal.raoa.elastic.model.AuthenticationId;
import ch.bergturbenthal.raoa.elastic.model.PersonalUserData;
import ch.bergturbenthal.raoa.elastic.model.User;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AuthorizationManager {
  boolean isUserAuthenticated(SecurityContext context);

  Optional<AuthenticationId> currentAuthentication(SecurityContext context);

  Flux<AlbumData> findVisibleAlbumsOfUser(User user);

  Mono<Boolean> canUserAccessToAlbum(SecurityContext context, UUID album);

  Mono<Boolean> canUserModifyAlbum(SecurityContext context, UUID album);

  Mono<Boolean> canUserModifyAlbum(Mono<User> user, UUID album);

  @NotNull
  Mono<Boolean> canUserAccessToAlbum(UUID album, Mono<User> currentUser);

  Mono<Boolean> canUserManageUsers(SecurityContext context);

  @NotNull
  Mono<User> currentUser(SecurityContext context);

  PersonalUserData readPersonalUserData(SecurityContext context);

  Mono<Boolean> hasPendingRequest(SecurityContext context);
}
