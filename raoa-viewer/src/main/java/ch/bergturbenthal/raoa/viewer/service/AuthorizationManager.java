package ch.bergturbenthal.raoa.viewer.service;

import ch.bergturbenthal.raoa.viewer.model.usermanager.PersonalUserData;
import ch.bergturbenthal.raoa.viewer.model.usermanager.User;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Mono;

public interface AuthorizationManager {
  boolean isUserAuthenticated(SecurityContext context);

  Mono<Boolean> canUserAccessToAlbum(SecurityContext context, UUID album);

  Mono<Boolean> canUserManageUsers(SecurityContext context);

  @NotNull
  Mono<User> currentUser(SecurityContext context);

  PersonalUserData readPersonalUserData(SecurityContext context);

  boolean hasPendingRequest(SecurityContext context);
}
