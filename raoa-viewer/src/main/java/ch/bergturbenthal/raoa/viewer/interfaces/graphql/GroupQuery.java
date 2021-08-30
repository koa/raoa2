package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.elastic.model.Group;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.viewer.model.graphql.*;
import graphql.kickstart.tools.GraphQLResolver;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
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

  public CompletableFuture<List<UserMembershipReference>> members(GroupReference groupReference) {
    return dataViewService
        .listUsers()
        .flatMapIterable(
            u ->
                () ->
                    u.getGroupMembership().stream()
                        .filter(membership -> membership.getGroup().equals(groupReference.getId()))
                        .map(
                            membership ->
                                new UserMembershipReference(
                                    membership.getFrom(),
                                    membership.getUntil(),
                                    new UserReference(
                                        u.getId(), u.getUserData(), groupReference.getContext())))
                        .iterator())
        .collectList()
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<List<LabelValue>> labels(GroupReference groupReference) {
    return groupReference
        .getGroup()
        .map(
            g ->
                Optional.ofNullable(g.getLabels()).map(Map::entrySet).stream()
                    .flatMap(Collection::stream)
                    .map(e -> new LabelValue(e.getKey(), e.getValue()))
                    .collect(Collectors.toList()))
        .timeout(TIMEOUT)
        .toFuture();
  }
}
