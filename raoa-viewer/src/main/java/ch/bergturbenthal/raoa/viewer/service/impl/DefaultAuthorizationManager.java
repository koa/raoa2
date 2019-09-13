package ch.bergturbenthal.raoa.viewer.service.impl;

import ch.bergturbenthal.raoa.viewer.model.usermanager.AuthenticationId;
import ch.bergturbenthal.raoa.viewer.model.usermanager.PersonalUserData;
import ch.bergturbenthal.raoa.viewer.model.usermanager.User;
import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import ch.bergturbenthal.raoa.viewer.service.UserManager;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
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

  @Override
  public Optional<AuthenticationId> currentAuthentication(SecurityContext context) {
    final Authentication authentication = context.getAuthentication();
    if (authentication == null) return Optional.empty();
    final Authentication userAuthentication;
    if (authentication instanceof OAuth2Authentication) {
      userAuthentication = ((OAuth2Authentication) authentication).getUserAuthentication();
    } else userAuthentication = authentication;
    final Object principal = userAuthentication.getPrincipal();

    if (principal instanceof Jwt) {
      final Map<String, Object> claims = ((Jwt) principal).getClaims();
      final String subject = (String) claims.get("sub");
      String authorizedClientRegistrationId = (String) claims.get("iss");
      return Optional.of(
          AuthenticationId.builder().authority(authorizedClientRegistrationId).id(subject).build());
    }

    return Optional.empty();
    /*
       if (principal instanceof DefaultOAuth2User
               && userAuthentication instanceof OAuth2AuthenticationToken) {
         final String authorizedClientRegistrationId =
                 ((OAuth2AuthenticationToken) userAuthentication).getAuthorizedClientRegistrationId();
         final String subject = (String) ((DefaultOAuth2User) principal).getAttributes().get("sub");
         return Optional.of(
                 AuthenticationId.builder().authority(authorizedClientRegistrationId).id(subject).build());
       } else return Optional.empty();

    */
  }

  @Override
  public Mono<Boolean> canUserAccessToAlbum(final SecurityContext context, final UUID album) {
    return currentUser(context)
        // .log("current user")
        .map(
            u ->
                u.isSuperuser()
                    || (u.getVisibleAlbums() != null && u.getVisibleAlbums().contains(album)))
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
            authenticationId ->
                userManager
                    .findUserForAuthentication(authenticationId)
                    .switchIfEmpty(
                        Mono.defer(
                            () -> {
                              if (viewerProperties
                                  .getSuperuser()
                                  .equals(authenticationId.getId())) {
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
                            })))
        .orElse(Mono.empty());
  }

  @Override
  public PersonalUserData readPersonalUserData(final SecurityContext context) {
    final Object principal = context.getAuthentication().getPrincipal();
    final Jwt oidcUser = (Jwt) principal;
    final Map<String, Object> attributes = oidcUser.getClaims();
    return PersonalUserData.builder()
        .comment("")
        .picture((String) attributes.get("picture"))
        .name((String) attributes.get("name"))
        .email((String) attributes.get("email"))
        .emailVerified((Boolean) attributes.get("email_verified"))
        .build();
  }

  @Override
  public boolean hasPendingRequest(final SecurityContext context) {
    return currentAuthentication(context).map(userManager::hasPendingRequest).orElse(false);
  }
}
