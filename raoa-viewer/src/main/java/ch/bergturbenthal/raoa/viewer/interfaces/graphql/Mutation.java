package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.viewer.model.graphql.RequestAccessResult;
import ch.bergturbenthal.raoa.viewer.model.graphql.RequestAccessResultCode;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AccessRequest;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AuthenticationId;
import ch.bergturbenthal.raoa.viewer.model.usermanager.PersonalUserData;
import ch.bergturbenthal.raoa.viewer.service.UserManager;
import com.coxautodev.graphql.tools.GraphQLMutationResolver;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class Mutation implements GraphQLMutationResolver {
  private final UserManager userManager;

  public Mutation(final UserManager userManager) {
    this.userManager = userManager;
  }

  CompletableFuture<? extends RequestAccessResult> requestAccess(String comment) {
    final SecurityContext context = SecurityContextHolder.getContext();
    final Authentication authentication = context.getAuthentication();
    final Object principal = authentication.getPrincipal();
    final Mono<RequestAccessResultCode> success;
    if (principal instanceof OidcUser && authentication instanceof OAuth2AuthenticationToken) {
      final OAuth2AuthenticationToken oAuth2AuthenticationToken =
          (OAuth2AuthenticationToken) authentication;
      final OidcUser oidcUser = (OidcUser) principal;
      final AuthenticationId authenticationId =
          AuthenticationId.builder()
              .authority(oAuth2AuthenticationToken.getAuthorizedClientRegistrationId())
              .id(oidcUser.getSubject())
              .build();
      success =
          userManager
              .findUserForAuthentication(authenticationId)
              .map(u -> false)
              .defaultIfEmpty(true)
              .filter(b -> b)
              .map(
                  b -> {
                    final AccessRequest.AccessRequestBuilder builder = AccessRequest.builder();
                    builder.authenticationId(authenticationId);
                    builder.comment(comment);
                    builder.requestTime(Instant.now());

                    final PersonalUserData userData =
                        PersonalUserData.builder()
                            .name(oidcUser.getFullName())
                            .picture(oidcUser.getPicture())
                            .comment(comment)
                            .email(oidcUser.getEmail())
                            .emailVerified(oidcUser.getEmailVerified())
                            .build();
                    builder.userData(userData);
                    userManager.requestAccess(builder.build());
                    return RequestAccessResultCode.OK;
                  })
              .defaultIfEmpty(RequestAccessResultCode.ALREADY_ACCEPTED);
    } else success = Mono.just(RequestAccessResultCode.NOT_LOGGED_IN);
    return success
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
        .toFuture();
  }
}
