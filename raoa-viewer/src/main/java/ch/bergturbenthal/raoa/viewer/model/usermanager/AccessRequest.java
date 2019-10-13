package ch.bergturbenthal.raoa.viewer.model.usermanager;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "request-access")
@Value
@JsonDeserialize(builder = AccessRequest.AccessRequestBuilder.class)
public class AccessRequest {
  @Id
  @Field(index = false)
  private String requestId;

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

  @Builder
  public AccessRequest(
      final PersonalUserData userData,
      final AuthenticationId authenticationId,
      final String comment,
      final Instant requestTime,
      final UUID requestedAlbum) {
    this.userData = userData;
    this.authenticationId = authenticationId;
    this.comment = comment;
    this.requestTime = requestTime;
    this.requestedAlbum = requestedAlbum;
    this.requestId = concatId(authenticationId);
  }

  @NotNull
  public static String concatId(final AuthenticationId authenticationId) {
    return authenticationId.getAuthority() + ";" + authenticationId.getId();
  }

  @JsonPOJOBuilder(withPrefix = "")
  public static class AccessRequestBuilder {}
}
