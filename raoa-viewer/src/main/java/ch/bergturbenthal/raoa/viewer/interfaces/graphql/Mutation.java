package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.viewer.model.graphql.AuthenticationState;
import ch.bergturbenthal.raoa.viewer.model.graphql.RequestAccessResult;
import ch.bergturbenthal.raoa.viewer.model.graphql.RequestAccessResultCode;
import ch.bergturbenthal.raoa.viewer.model.graphql.UserReference;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AccessRequest;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AuthenticationId;
import ch.bergturbenthal.raoa.viewer.model.usermanager.PersonalUserData;
import ch.bergturbenthal.raoa.viewer.model.usermanager.User;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import ch.bergturbenthal.raoa.viewer.service.UserManager;
import com.coxautodev.graphql.tools.GraphQLMutationResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class Mutation implements GraphQLMutationResolver {
  private static final Duration TIMEOUT = Duration.ofMinutes(5);
  private final UserManager userManager;
  private final AuthorizationManager authorizationManager;
  private final QueryContextSupplier queryContextSupplier;

  public Mutation(
      final UserManager userManager,
      final AuthorizationManager authorizationManager,
      final QueryContextSupplier queryContextSupplier) {
    this.userManager = userManager;
    this.authorizationManager = authorizationManager;
    this.queryContextSupplier = queryContextSupplier;
  }

  public CompletableFuture<UserReference> createUser(AuthenticationId authenticationId) {
    return queryContextSupplier
        .createContext()
        .flatMap(
            queryContext -> {
              if (!queryContext.canUserManageUsers()) return Mono.empty();
              final Mono<User> newUser = userManager.createNewUser(authenticationId);
              return newUser.map(u -> new UserReference(u.getId(), u.getUserData(), queryContext));
            })
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<? extends RequestAccessResult> requestAccess(String comment) {
    return queryContextSupplier
        .createContext()
        .map(
            queryContext -> {
              final Optional<AuthenticationId> authenticationId =
                  queryContext.currentAuthenticationId();
              final AuthenticationState authenticationState = queryContext.getAuthenticationState();
              if (authenticationState == AuthenticationState.AUTHENTICATED
                  && authenticationId.isPresent()) {
                final PersonalUserData personalUserData =
                    authorizationManager.readPersonalUserData(queryContext.getSecurityContext());
                final AccessRequest.AccessRequestBuilder builder = AccessRequest.builder();
                builder.authenticationId(authenticationId.get());
                builder.comment(comment);
                builder.requestTime(Instant.now());

                builder.userData(personalUserData);
                userManager.requestAccess(builder.build());
                return RequestAccessResultCode.OK;
              }
              if (authenticationState == AuthenticationState.AUTHORIZED)
                return RequestAccessResultCode.ALREADY_ACCEPTED;
              return RequestAccessResultCode.NOT_LOGGED_IN;
            })
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
        .timeout(TIMEOUT)
        .toFuture();
  }
}
