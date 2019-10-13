package ch.bergturbenthal.raoa.viewer.model.usermanager;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "request-access")
@Value
@Builder
public class AccessRequest {
  @Field(type = FieldType.Nested)
  private PersonalUserData userData;

  @Id
  @Field(type = FieldType.Nested)
  private AuthenticationId authenticationId;

  @Field(type = FieldType.Text)
  private String comment;

  @Field(type = FieldType.Double)
  private Instant requestTime;

  @Field(type = FieldType.Keyword)
  private UUID requestedAlbum;
}
