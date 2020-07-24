package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.elastic.model.Group;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.viewer.model.graphql.Album;
import ch.bergturbenthal.raoa.viewer.model.graphql.GroupReference;
import ch.bergturbenthal.raoa.viewer.model.graphql.UserReference;
import com.coxautodev.graphql.tools.GraphQLResolver;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GroupQuery implements GraphQLResolver<GroupReference> {
  private static final Duration TIMEOUT = Duration.ofMinutes(5);
  private final DataViewService dataViewService;

  public GroupQuery(final DataViewService dataViewService) {
    this.dataViewService = dataViewService;
  }

  public CompletableFuture<String> getName(GroupReference group) {
    return group.getGroup().map(Group::getName).timeout(TIMEOUT).toFuture();
  }

  public CompletableFuture<List<Album>> canAccess(GroupReference groupReference) {
    return groupReference
        .getGroup()
        .flatMapIterable(Group::getVisibleAlbums)
        .map(
            id -> new Album(id, groupReference.getContext(), dataViewService.readAlbum(id).cache()))
        .collectList()
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<List<UserReference>> members(GroupReference groupReference) {
    return dataViewService
        .listUsers()
        .filter(u -> u.getGroupMembership().contains(groupReference.getId()))
        .map(u -> new UserReference(u.getId(), u.getUserData(), groupReference.getContext()))
        .collectList()
        .timeout(TIMEOUT)
        .toFuture();
  }
}
