package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.elastic.model.AuthenticationId;
import ch.bergturbenthal.raoa.elastic.model.User;
import ch.bergturbenthal.raoa.viewer.model.graphql.AuthenticationState;
import ch.bergturbenthal.raoa.viewer.model.graphql.QueryContext;
import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import java.util.Optional;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class QueryContextSupplier {
  private final AuthorizationManager authorizationManager;
  private ViewerProperties viewerProperties;

  public QueryContextSupplier(
      final AuthorizationManager authorizationManager, ViewerProperties viewerProperties) {
    this.authorizationManager = authorizationManager;

    this.viewerProperties = viewerProperties;
  }

  public Mono<QueryContext> createContext() {
    final SecurityContext context = SecurityContextHolder.getContext();
    final Optional<AuthenticationId> authenticationId =
        authorizationManager.currentAuthentication(context);
    final RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
    return authorizationManager
        .currentUser(context)
        .cache()
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty())
        .map(
            user ->
                new QueryContext() {

                  private final Mono<AuthenticationState> currentAuthenticationState =
                      findCurrentAuthenticationState(user, context);

                  @Override
                  public SecurityContext getSecurityContext() {
                    return context;
                  }

                  @Override
                  public Mono<AuthenticationState> getAuthenticationState() {
                    return currentAuthenticationState;
                  }

                  @Override
                  public Optional<User> getCurrentUser() {
                    return user;
                  }

                  @Override
                  public Optional<AuthenticationId> currentAuthenticationId() {
                    return authenticationId;
                  }

                  @Override
                  public boolean canUserManageUsers() {
                    return user.map(User::isSuperuser).orElse(false);
                  }

                  @Override
                  public boolean canUserEditData() {
                    return user.map(User::isEditor).orElse(false);
                  }

                  @Override
                  public RequestAttributes getRequestAttributes() {
                    return requestAttributes;
                  }

                  @Override
                  public String getContexRootPath() {
                    if (requestAttributes instanceof ServletRequestAttributes) {
                      HttpServletRequest request =
                          ((ServletRequestAttributes) requestAttributes).getRequest();
                      final String servletPath = request.getServletPath();

                      final String currentRequestPath = request.getRequestURL().toString();
                      return currentRequestPath.substring(
                          0, currentRequestPath.length() - servletPath.length());
                    } else return "";
                  }

                  @Override
                  public boolean canAccessGroup(final UUID groupId) {
                    return user.map(
                            user1 ->
                                user1.isSuperuser() || user1.getGroupMembership().contains(groupId))
                        .orElse(false);
                  }
                });
  }

  @NotNull
  private Mono<AuthenticationState> findCurrentAuthenticationState(
      final Optional<User> u, final SecurityContext context) {
    if (u.isPresent()) {
      return Mono.just(AuthenticationState.AUTHORIZED);
    }
    return authorizationManager
        .hasPendingRequest(context)
        .onErrorReturn(false)
        .map(
            hasPendingRequest -> {
              if (hasPendingRequest) return AuthenticationState.AUTHORIZATION_REQUESTED;
              if (authorizationManager.isUserAuthenticated(context)) {
                return AuthenticationState.AUTHENTICATED;
              }
              return AuthenticationState.UNKNOWN;
            });
  }
}
