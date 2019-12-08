package ch.bergturbenthal.raoa.libs.model.elasticsearch;

import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "user")
@Value
@Builder(toBuilder = true)
public class User {
  @Id private UUID id;

  @Field(type = FieldType.Object)
  private PersonalUserData userData;

  @Field(type = FieldType.Keyword)
  private Set<UUID> visibleAlbums;

  @Field(type = FieldType.Object)
  private Set<AuthenticationId> authentications;

  @Field(type = FieldType.Boolean)
  private boolean superuser;
}
