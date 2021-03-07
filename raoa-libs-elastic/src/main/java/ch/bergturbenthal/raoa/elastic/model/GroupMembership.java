package ch.bergturbenthal.raoa.elastic.model;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Value
@Builder(toBuilder = true)
public class GroupMembership {
  @Field(type = FieldType.Double)
  Instant from;

  @Field(type = FieldType.Double)
  Instant until;

  @Field(type = FieldType.Keyword)
  UUID group;
}
