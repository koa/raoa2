package ch.bergturbenthal.raoa.viewer.model.usermanager;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Value
@Builder
public class AccessRequest {
  @Field(type = FieldType.Nested)
  private PersonalUserData userData;

  @Field(type = FieldType.Nested)
  private AuthenticationId authenticationId;

  @Field(type = FieldType.Text)
  private String comment;

  @Field(type = FieldType.Double)
  private Instant requestTime;

  @Field(type = FieldType.Keyword)
  private UUID requestedAlbum;
}
