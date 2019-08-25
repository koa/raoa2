package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.viewer.model.graphql.AuthenticationState;
import ch.bergturbenthal.raoa.viewer.model.graphql.QueryContext;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AuthenticationId;
import ch.bergturbenthal.raoa.viewer.model.usermanager.User;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import java.util.Optional;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import reactor.core.publisher.Mono;

@Component
public class QueryContextSupplier {
  private final AuthorizationManager authorizationManager;

  public QueryContextSupplier(final AuthorizationManager authorizationManager) {
    this.authorizationManager = authorizationManager;
  }

  public Mono<QueryContext> createContext() {
    final SecurityContext context = SecurityContextHolder.getContext();
    final Mono<User> user = authorizationManager.currentUser(context);
    final Optional<AuthenticationId> authenticationId =
        authorizationManager.currentAuthentication(context);
    final RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
    return user.map(Optional::of)
        .defaultIfEmpty(Optional.empty())
        .map(
            u ->
                new QueryContext() {

                  private final AuthenticationState currentAuthenticationState =
                      findCurrentAuthenticationState(u, context);

                  @Override
                  public SecurityContext getSecurityContext() {
                    return context;
                  }

                  @Override
                  public AuthenticationState getAuthenticationState() {
                    return currentAuthenticationState;
                  }

                  @Override
                  public Optional<User> getCurrentUser() {
                    return u;
                  }

                  @Override
                  public Optional<AuthenticationId> currentAuthenticationId() {
                    return authenticationId;
                  }

                  @Override
                  public boolean canUserManageUsers() {
                    return u.map(User::isSuperuser).orElse(false);
                  }

                  @Override
                  public boolean canAccessAlbum(final UUID albumId) {
                    return u.map(
                            user -> user.isSuperuser() || user.getVisibleAlbums().contains(albumId))
                        .orElse(false);
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
                });
  }

  @NotNull
  private AuthenticationState findCurrentAuthenticationState(
      final Optional<User> u, final SecurityContext context) {
    if (u.isPresent()) {
      return AuthenticationState.AUTHORIZED;
    }
    if (authorizationManager.hasPendingRequest(context)) {
      return AuthenticationState.AUTHORIZATION_REQUESTED;
    }
    if (authorizationManager.isUserAuthenticated(context)) {
      return AuthenticationState.AUTHENTICATED;
    }
    return AuthenticationState.UNKNOWN;
  }
}
