package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.viewer.model.graphql.Album;
import ch.bergturbenthal.raoa.viewer.model.graphql.UserReference;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AuthenticationId;
import ch.bergturbenthal.raoa.viewer.model.usermanager.User;
import ch.bergturbenthal.raoa.viewer.service.DataViewService;
import com.coxautodev.graphql.tools.GraphQLResolver;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class UserQuery implements GraphQLResolver<UserReference> {
  private static final Duration TIMEOUT = Duration.ofMinutes(5);
  private final DataViewService dataViewService;

  public UserQuery(final DataViewService dataViewService) {
    this.dataViewService = dataViewService;
  }

  public CompletableFuture<List<Album>> canAccess(UserReference user) {
    if (canShowUserDetails(user)) {
      return dataViewService
          .findUserById(user.getId())
          .filter(u -> u.getVisibleAlbums() != null)
          .flatMapIterable(User::getVisibleAlbums)
          .filterWhen(id -> dataViewService.readAlbum(id).map(a -> true).defaultIfEmpty(false))
          .map(id -> new Album(id, user.getContext(), dataViewService.readAlbum(id).cache()))
          .collectList()
          .timeout(TIMEOUT)
          .toFuture();
    }
    return CompletableFuture.completedFuture(Collections.emptyList());
  }

  public CompletableFuture<Boolean> canManageUsers(UserReference user) {
    if (Objects.equals(
        user.getId(), user.getContext().getCurrentUser().map(User::getId).orElse(null))) {
      return CompletableFuture.completedFuture(user.getContext().canUserManageUsers());
    }
    if (canShowUserDetails(user)) {
      return dataViewService
          .findUserById(user.getId())
          .map(User::isSuperuser)
          .timeout(TIMEOUT)
          .toFuture();
    }
    return CompletableFuture.completedFuture(null);
  }

  private boolean canShowUserDetails(UserReference userReference) {
    if (userReference.getContext().canUserManageUsers()) {
      return true;
    }
    final Optional<User> currentUser = userReference.getContext().getCurrentUser();
    return currentUser.map(u -> u.getId().equals(userReference.getId())).orElse(false);
  }

  public CompletableFuture<List<AuthenticationId>> getAuthentications(UserReference user) {
    if (canShowUserDetails(user)) {
      return dataViewService
          .findUserById(user.getId())
          .flatMapIterable(User::getAuthentications)
          .collectList()
          .timeout(TIMEOUT)
          .toFuture();
    }
    return CompletableFuture.completedFuture(Collections.emptyList());
  }
}
