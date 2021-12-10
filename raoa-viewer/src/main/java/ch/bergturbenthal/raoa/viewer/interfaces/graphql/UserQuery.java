package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.elastic.model.AlbumData;
import ch.bergturbenthal.raoa.elastic.model.AuthenticationId;
import ch.bergturbenthal.raoa.elastic.model.User;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.viewer.model.graphql.*;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Controller
public class UserQuery {
  private static final String TYPE_NAME = "User";
  private final DataViewService dataViewService;
  private final AuthorizationManager authorizationManager;

  public UserQuery(
      final DataViewService dataViewService, final AuthorizationManager authorizationManager) {
    this.dataViewService = dataViewService;
    this.authorizationManager = authorizationManager;
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public Flux<Album> canAccess(UserReference user) {
    return getVisibleAlbums(user)
        .map(a -> new Album(a.getRepositoryId(), user.getContext(), Mono.just(a)));
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public Flux<Album> canAccessDirect(UserReference user) {
    return getDirectVisibleAlbums(user)
        .map(a -> new Album(a, user.getContext(), dataViewService.readAlbum(a).cache()));
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

  @SchemaMapping(typeName = TYPE_NAME)
  public Mono<Album> newestAlbumCanAccess(UserReference user) {

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
        .map(a -> new Album(a.getRepositoryId(), user.getContext(), Mono.just(a)));
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public Mono<Boolean> canManageUsers(UserReference user) {
    if (Objects.equals(
        user.getId(), user.getContext().getCurrentUser().map(User::getId).orElse(null))) {
      return Mono.just(user.getContext().canUserManageUsers());
    }
    if (canShowUserDetails(user)) {
      return dataViewService.findUserById(user.getId()).map(User::isSuperuser);
    }
    return Mono.just(Boolean.FALSE);
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public Mono<Boolean> canEdit(UserReference user) {
    final QueryContext context = user.getContext();
    if (Objects.equals(user.getId(), context.getCurrentUser().map(User::getId).orElse(null))) {
      final boolean value = context.canUserEditData() || context.canUserManageUsers();
      return Mono.just(value);
    }
    if (canShowUserDetails(user)) {
      return dataViewService.findUserById(user.getId()).map(u -> u.isEditor() || u.isSuperuser());
    }
    return Mono.just(false);
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public Mono<Boolean> isEditor(UserReference user) {
    final QueryContext context = user.getContext();
    if (Objects.equals(user.getId(), context.getCurrentUser().map(User::getId).orElse(null))) {
      return Mono.just(context.canUserEditData());
    }
    if (canShowUserDetails(user)) {
      return dataViewService
          .findUserById(user.getId())
          //                            .log("editor")
          .map(User::isEditor);
    }
    return Mono.just(false);
  }

  private boolean canShowUserDetails(UserReference userReference) {
    if (userReference.getContext().canUserManageUsers()) {
      return true;
    }
    final Optional<User> currentUser = userReference.getContext().getCurrentUser();
    return currentUser.map(u -> u.getId().equals(userReference.getId())).orElse(false);
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public Flux<AuthenticationId> authentications(UserReference user) {
    if (canShowUserDetails(user)) {
      return dataViewService.findUserById(user.getId()).flatMapIterable(User::getAuthentications);
    }
    return Flux.empty();
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public Flux<GroupMembershipReference> groups(UserReference user) {
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
              });
    }
    return Flux.empty();
  }
}
