package ch.bergturbenthal.raoa.viewer.model.graphql;

import ch.bergturbenthal.raoa.elastic.model.Group;
import java.util.UUID;
import lombok.Value;
import reactor.core.publisher.Mono;

@Value
public class GroupReference {
  UUID id;
  QueryContext context;
  Mono<Group> group;
}
