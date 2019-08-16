package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.viewer.model.graphql.Album;
import ch.bergturbenthal.raoa.viewer.model.graphql.UserReference;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AuthenticationId;
import ch.bergturbenthal.raoa.viewer.model.usermanager.User;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import ch.bergturbenthal.raoa.viewer.service.UserManager;
import com.coxautodev.graphql.tools.GraphQLResolver;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class UserQuery implements GraphQLResolver<UserReference> {
  private final AuthorizationManager authorizationManager;
  private final UserManager userManager;
  private final AlbumList albumList;

  public UserQuery(
      final AuthorizationManager authorizationManager,
      final UserManager userManager,
      final AlbumList albumList) {
    this.authorizationManager = authorizationManager;
    this.userManager = userManager;
    this.albumList = albumList;
  }

  public CompletableFuture<List<Album>> canAccess(UserReference user) {
    final SecurityContext context = SecurityContextHolder.getContext();
    return canAccessUsers()
        .flatMap(t -> userManager.findUserById(user.getId()))
        .filter(u -> u.getVisibleAlbums() != null)
        .flatMapIterable(User::getVisibleAlbums)
        .filterWhen(id -> albumList.getAlbum(id).map(a -> true).defaultIfEmpty(false))
        .map(id -> new Album(id, user.getContext()))
        .collectList()
        .toFuture();
  }

  @NotNull
  private Mono<Boolean> canAccessUsers() {
    return authorizationManager
        .canUserManageUsers(SecurityContextHolder.getContext())
        .filter(t -> t);
  }

  public CompletableFuture<List<AuthenticationId>> getAuthentications(UserReference user) {
    return canAccessUsers()
        .flatMap(t -> userManager.findUserById(user.getId()))
        .flatMapIterable(User::getAuthentications)
        .collectList()
        .toFuture();
  }
}
