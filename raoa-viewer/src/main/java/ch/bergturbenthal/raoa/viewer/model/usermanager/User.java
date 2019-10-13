package ch.bergturbenthal.raoa.viewer.model.usermanager;

import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Value
@Builder(toBuilder = true)
public class User {
  @Id private UUID id;

  @Field(type = FieldType.Nested)
  private PersonalUserData userData;

  @Field(type = FieldType.Keyword)
  private Set<UUID> visibleAlbums;

  @Field(type = FieldType.Nested)
  private Set<AuthenticationId> authentications;

  @Field(type = FieldType.Boolean)
  private boolean superuser;
}
