package ch.bergturbenthal.raoa.viewer.service;

import ch.bergturbenthal.raoa.libs.model.elasticsearch.AuthenticationId;
import ch.bergturbenthal.raoa.libs.model.elasticsearch.PersonalUserData;
import ch.bergturbenthal.raoa.libs.model.elasticsearch.User;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Mono;

public interface AuthorizationManager {
  boolean isUserAuthenticated(SecurityContext context);

  Optional<AuthenticationId> currentAuthentication(SecurityContext context);

  Mono<Boolean> canUserAccessToAlbum(SecurityContext context, UUID album);

  Mono<Boolean> canUserManageUsers(SecurityContext context);

  @NotNull
  Mono<User> currentUser(SecurityContext context);

  PersonalUserData readPersonalUserData(SecurityContext context);

  Mono<Boolean> hasPendingRequest(SecurityContext context);
}
