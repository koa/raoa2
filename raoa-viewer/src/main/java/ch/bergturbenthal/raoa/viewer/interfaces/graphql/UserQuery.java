package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.viewer.model.graphql.Album;
import ch.bergturbenthal.raoa.viewer.model.graphql.UserReference;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AuthenticationId;
import ch.bergturbenthal.raoa.viewer.model.usermanager.User;
import ch.bergturbenthal.raoa.viewer.service.UserManager;
import com.coxautodev.graphql.tools.GraphQLResolver;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;

@Component
public class UserQuery implements GraphQLResolver<UserReference> {
  private final UserManager userManager;
  private final AlbumList albumList;

  public UserQuery(final UserManager userManager, final AlbumList albumList) {
    this.userManager = userManager;
    this.albumList = albumList;
  }

  public CompletableFuture<List<Album>> canAccess(UserReference user) {
    if (canShowUserDetails(user)) {
      return userManager
          .findUserById(user.getId())
          .filter(u -> u.getVisibleAlbums() != null)
          .flatMapIterable(User::getVisibleAlbums)
          .filterWhen(id -> albumList.getAlbum(id).map(a -> true).defaultIfEmpty(false))
          .map(id -> new Album(id, user.getContext()))
          .collectList()
          .toFuture();
    }
    return CompletableFuture.completedFuture(Collections.emptyList());
  }

  public CompletableFuture<Boolean> canManageUsers(UserReference user) {
    if (canShowUserDetails(user)) {
      return userManager.findUserById(user.getId()).map(User::isSuperuser).toFuture();
    }
    return CompletableFuture.completedFuture(null);
  }

  private boolean canShowUserDetails(UserReference userReference) {
    if (userReference.getContext().canUserManageUsers()) return true;
    return userReference
        .getContext()
        .getCurrentUser()
        .map(u -> u.getId().equals(userReference.getId()))
        .orElse(false);
  }

  public CompletableFuture<List<AuthenticationId>> getAuthentications(UserReference user) {
    if (canShowUserDetails(user)) {
      return userManager
          .findUserById(user.getId())
          .flatMapIterable(User::getAuthentications)
          .collectList()
          .toFuture();
    }
    return CompletableFuture.completedFuture(Collections.emptyList());
  }
}
