package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.elastic.model.AlbumData;
import ch.bergturbenthal.raoa.elastic.model.AuthenticationId;
import ch.bergturbenthal.raoa.elastic.model.User;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.viewer.model.graphql.Album;
import ch.bergturbenthal.raoa.viewer.model.graphql.GroupMembershipReference;
import ch.bergturbenthal.raoa.viewer.model.graphql.GroupReference;
import ch.bergturbenthal.raoa.viewer.model.graphql.UserReference;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import com.coxautodev.graphql.tools.GraphQLResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class UserQuery implements GraphQLResolver<UserReference> {
  private static final Duration TIMEOUT = Duration.ofMinutes(5);
  private final DataViewService dataViewService;
  private final AuthorizationManager authorizationManager;

  public UserQuery(
      final DataViewService dataViewService, final AuthorizationManager authorizationManager) {
    this.dataViewService = dataViewService;
    this.authorizationManager = authorizationManager;
  }

  public CompletableFuture<List<Album>> canAccess(UserReference user) {
    return getVisibleAlbums(user)
        .map(a -> new Album(a.getRepositoryId(), user.getContext(), Mono.just(a)))
        .collectList()
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<List<Album>> canAccessDirect(UserReference user) {
    return getDirectVisibleAlbums(user)
        .map(a -> new Album(a, user.getContext(), dataViewService.readAlbum(a).cache()))
        .collectList()
        .timeout(TIMEOUT)
        .toFuture();
  }

  private Flux<UUID> getDirectVisibleAlbums(final UserReference user) {
    if (!canShowUserDetails(user)) {
      return Flux.empty();
    }

    return dataViewService
        .findUserById(user.getId())
        // .log("user")
        .flatMapIterable(User::getVisibleAlbums);
  }

  private Flux<AlbumData> getVisibleAlbums(final UserReference user) {
    if (!canShowUserDetails(user)) {
      return Flux.empty();
    }

    return dataViewService
        .findUserById(user.getId())
        // .log("user")
        .flatMapMany(authorizationManager::findVisibleAlbumsOfUser);
  }

  public CompletableFuture<Album> newestAlbumCanAccess(UserReference user) {

    return getVisibleAlbums(user)
        .collect(
            Collectors.maxBy(
                Comparator.comparing(
                    albumData ->
                        albumData.getCreateTime() == null
                            ? Instant.MIN
                            : albumData.getCreateTime())))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .map(a -> new Album(a.getRepositoryId(), user.getContext(), Mono.just(a)))
        .timeout(TIMEOUT)
        .toFuture();
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

  public CompletableFuture<List<GroupMembershipReference>> groups(UserReference user) {
    if (canShowUserDetails(user)) {
      return dataViewService
          .findUserById(user.getId())
          .flatMapIterable(User::getGroupMembership)
          .map(
              membership -> {
                final GroupReference groupReference =
                    new GroupReference(
                        membership.getGroup(),
                        user.getContext(),
                        dataViewService.findGroupById(membership.getGroup()).cache());
                return new GroupMembershipReference(
                    membership.getFrom(), membership.getUntil(), groupReference);
              })
          .collectList()
          .timeout(TIMEOUT)
          .toFuture();
    }
    return CompletableFuture.completedFuture(Collections.emptyList());
  }
}
