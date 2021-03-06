package ch.bergturbenthal.raoa.elastic.model;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Value
@Builder(toBuilder = true)
public class GroupMembership {
  Optional<Instant> from;
  Optional<Instant> until;

  @Field(type = FieldType.Keyword)
  UUID group;
}
