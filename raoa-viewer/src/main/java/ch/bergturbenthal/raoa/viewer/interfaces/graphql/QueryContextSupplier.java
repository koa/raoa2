package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.viewer.model.graphql.AuthenticationState;
import ch.bergturbenthal.raoa.viewer.model.graphql.QueryContext;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AuthenticationId;
import ch.bergturbenthal.raoa.viewer.model.usermanager.User;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

@Component
public class QueryContextSupplier {
  private final AuthorizationManager authorizationManager;
  private final Mono<UUID> latestAlbum;

  public QueryContextSupplier(
      final AuthorizationManager authorizationManager, AlbumList albumList) {
    this.authorizationManager = authorizationManager;
    latestAlbum =
        albumList
            .listAlbums()
            .flatMap(
                a ->
                    a.getAccess()
                        .readAutoadd()
                        .reduce((t1, t2) -> t1.isAfter(t2) ? t1 : t2)
                        .map(d -> Tuples.of(d, a.getAlbumId())))
            .collect(Collectors.maxBy(Comparator.comparing(Tuple2::getT1)))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(Tuple2::getT2)
            .cache(Duration.ofMinutes(5));
  }

  public Mono<QueryContext> createContext() {
    final SecurityContext context = SecurityContextHolder.getContext();
    final Mono<User> user = authorizationManager.currentUser(context);
    final Optional<AuthenticationId> authenticationId =
        authorizationManager.currentAuthentication(context);
    final RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
    final Mono<Optional<User>> userMono = user.map(Optional::of).defaultIfEmpty(Optional.empty());
    return Mono.zip(userMono, latestAlbum)
        .map(
            t -> {
              final Optional<User> u = t.getT1();
              final UUID latestAlbum = t.getT2();
              return new QueryContext() {

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
                  return albumId.equals(latestAlbum)
                      || u.map(
                              user ->
                                  user.isSuperuser() || user.getVisibleAlbums().contains(albumId))
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
              };
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
