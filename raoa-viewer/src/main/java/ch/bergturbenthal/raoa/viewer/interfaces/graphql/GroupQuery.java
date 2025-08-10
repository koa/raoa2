package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.elastic.model.Group;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.viewer.model.graphql.*;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Controller
public class GroupQuery {
    private static final String TYPE_NAME = "Group";
    private final DataViewService dataViewService;

    public GroupQuery(final DataViewService dataViewService) {
        this.dataViewService = dataViewService;
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public Mono<String> name(GroupReference groupReference) {
        return groupReference.getGroup().map(Group::getName);
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public Flux<Album> canAccess(GroupReference groupReference) {
        return groupReference.getGroup().flatMapIterable(Group::getVisibleAlbums)
                .map(id -> new Album(id, groupReference.getContext(), dataViewService.readAlbum(id).cache()));
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public Flux<UserMembershipReference> members(GroupReference groupReference) {
        return dataViewService.listUsers()
                .flatMapIterable(u -> () -> u.getGroupMembership().stream()
                        .filter(membership -> membership.getGroup().equals(groupReference.getId()))
                        .map(membership -> new UserMembershipReference(membership.getFrom(), membership.getUntil(),
                                new UserReference(u.getId(), u.getUserData(), groupReference.getContext())))
                        .iterator());
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public Flux<LabelValue> labels(GroupReference groupReference) {
        return groupReference.getGroup()
                .flatMapIterable(g -> () -> Optional.ofNullable(g.getLabels()).map(Map::entrySet).stream()
                        .flatMap(Collection::stream).map(e -> new LabelValue(e.getKey(), e.getValue())).iterator());
    }
}
