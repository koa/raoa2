package ch.bergturbenthal.raoa.viewer.service.impl;

import ch.bergturbenthal.raoa.viewer.model.usermanager.AuthenticationId;
import ch.bergturbenthal.raoa.viewer.model.usermanager.PersonalUserData;
import ch.bergturbenthal.raoa.viewer.model.usermanager.User;
import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import ch.bergturbenthal.raoa.viewer.service.UserManager;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class DefaultAuthorizationManager implements AuthorizationManager {
  private final UserManager userManager;
  private final ViewerProperties viewerProperties;
  private final UUID virtualSuperuserId = UUID.randomUUID();

  public DefaultAuthorizationManager(
      final UserManager userManager, final ViewerProperties viewerProperties) {
    this.userManager = userManager;
    this.viewerProperties = viewerProperties;
  }

  @Override
  public boolean isUserAuthenticated(final SecurityContext context) {
    return currentAuthentication(context).isPresent();
  }

  private Optional<AuthenticationId> currentAuthentication(SecurityContext context) {
    final Authentication authentication = context.getAuthentication();
    final Object principal = authentication.getPrincipal();
    if (principal instanceof OidcUser && authentication instanceof OAuth2AuthenticationToken) {
      final String authorizedClientRegistrationId =
          ((OAuth2AuthenticationToken) authentication).getAuthorizedClientRegistrationId();
      final String subject = ((OidcUser) principal).getSubject();
      return Optional.of(
          AuthenticationId.builder().authority(authorizedClientRegistrationId).id(subject).build());
    } else return Optional.empty();
  }

  @Override
  public Mono<Boolean> canUserAccessToAlbum(final SecurityContext context, final UUID album) {
    return currentUser(context)
        .filter(u -> u.getVisibleAlbums() != null)
        .map(User::getVisibleAlbums)
        .map(a -> a.contains(album))
        .defaultIfEmpty(false);
  }

  @Override
  public Mono<Boolean> canUserManageUsers(final SecurityContext context) {
    return currentUser(context).map(User::isSuperuser).defaultIfEmpty(false);
  }

  @Override
  @NotNull
  public Mono<User> currentUser(final SecurityContext context) {
    return currentAuthentication(context)
        .map(
            authenticationId -> {
              return userManager
                  .findUserForAuthentication(authenticationId)
                  .switchIfEmpty(
                      Mono.defer(
                          () -> {
                            if (viewerProperties.getSuperuser().equals(authenticationId.getId())) {
                              return Mono.just(
                                  User.builder()
                                      .authentications(Collections.singleton(authenticationId))
                                      .id(virtualSuperuserId)
                                      .superuser(true)
                                      .userData(
                                          readPersonalUserData(context)
                                              .toBuilder()
                                              .comment("superuser created by config")
                                              .build())
                                      .build());

                            } else return Mono.empty();
                          }));
            })
        .orElse(Mono.empty());
  }

  @Override
  public PersonalUserData readPersonalUserData(final SecurityContext context) {
    final OidcUser oidcUser = (OidcUser) context.getAuthentication().getPrincipal();
    return PersonalUserData.builder()
        .comment("")
        .picture(oidcUser.getPicture())
        .name(oidcUser.getFullName())
        .email(oidcUser.getEmail())
        .emailVerified(oidcUser.getEmailVerified())
        .build();
  }

  @Override
  public boolean hasPendingRequest(final SecurityContext context) {
    return currentAuthentication(context).map(userManager::hasPendingRequest).orElse(false);
  }
}
