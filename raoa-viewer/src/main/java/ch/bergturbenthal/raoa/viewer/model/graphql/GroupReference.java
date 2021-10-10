package ch.bergturbenthal.raoa.viewer.model.graphql;

import java.util.UUID;
import lombok.Value;
import reactor.core.publisher.Mono;

@Value
public class GroupReference {
  UUID id;
  QueryContext context;
  Mono<ch.bergturbenthal.raoa.elastic.model.Group> group;
}
