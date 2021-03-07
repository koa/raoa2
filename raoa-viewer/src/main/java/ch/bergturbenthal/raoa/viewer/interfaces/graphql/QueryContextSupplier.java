package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.elastic.model.AuthenticationId;
import ch.bergturbenthal.raoa.elastic.model.User;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.viewer.model.graphql.AuthenticationState;
import ch.bergturbenthal.raoa.viewer.model.graphql.QueryContext;
import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

@Slf4j
@Component
public class QueryContextSupplier {
  private final AuthorizationManager authorizationManager;
  private final Mono<Optional<UUID>> latestAlbum;
  private ViewerProperties viewerProperties;

  public QueryContextSupplier(
      final AuthorizationManager authorizationManager,
      DataViewService dataViewService,
      ViewerProperties viewerProperties) {
    this.authorizationManager = authorizationManager;
    latestAlbum =
        dataViewService
            .listAlbums()
            .filter(a -> a.getCreateTime() != null)
            .map(e -> Tuples.of(e.getCreateTime(), e.getRepositoryId()))
            .collect(Collectors.maxBy(Comparator.comparing(Tuple2::getT1)))
            .filter(Optional::isPresent)
            .map(o -> o.map(Tuple2::getT2))
            .defaultIfEmpty(Optional.empty())
            // .log("latest album")
            .cache(Duration.ofMinutes(1));
    this.viewerProperties = viewerProperties;
  }

  public Mono<QueryContext> createContext() {
    final SecurityContext context = SecurityContextHolder.getContext();
    final Mono<User> user = authorizationManager.currentUser(context).cache();
    final Optional<AuthenticationId> authenticationId =
        authorizationManager.currentAuthentication(context);
    final RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
    final Mono<Optional<User>> userMono = user.map(Optional::of).defaultIfEmpty(Optional.empty());
    return Mono.zip(userMono, latestAlbum)
        .map(
            t -> {
              final Optional<User> u = t.getT1();
              final Optional<UUID> latestAlbum = t.getT2();
              return new QueryContext() {

                private final Mono<AuthenticationState> currentAuthenticationState =
                    findCurrentAuthenticationState(u, context);

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
                  return u.map(
                          user1 ->
                              user1.isSuperuser() || user1.getGroupMembership().contains(groupId))
                      .orElse(false);
                }
              };
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
