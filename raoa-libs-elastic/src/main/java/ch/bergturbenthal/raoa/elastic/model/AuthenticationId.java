package ch.bergturbenthal.raoa.elastic.model;

import lombok.Builder;
import lombok.Value;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Value
@Builder
public class AuthenticationId {
  @Field(type = FieldType.Keyword)
  private String authority;

  @Field(type = FieldType.Keyword)
  private String id;
}
