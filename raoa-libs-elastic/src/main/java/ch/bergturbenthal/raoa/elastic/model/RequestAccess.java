package ch.bergturbenthal.raoa.elastic.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "request-access", createIndex = true)
@Value
@JsonDeserialize(builder = RequestAccess.RequestAccessBuilder.class)
public class RequestAccess {
  @Id
  @Field(index = false)
  String requestId;

  @Field(type = FieldType.Nested)
  PersonalUserData userData;

  @Field(type = FieldType.Nested)
  AuthenticationId authenticationId;

  @Field(type = FieldType.Text)
  String comment;

  @Field(type = FieldType.Double)
  Instant requestTime;

  @Field(type = FieldType.Keyword)
  UUID requestedAlbum;

  @Builder
  public RequestAccess(
      final PersonalUserData userData,
      final AuthenticationId authenticationId,
      final String comment,
      final Instant requestTime,
      final UUID requestedAlbum,
      final String requestId) {
    this.userData = userData;
    this.authenticationId = authenticationId;
    this.comment = comment;
    this.requestTime = requestTime;
    this.requestedAlbum = requestedAlbum;
    this.requestId = concatId(authenticationId);
  }

  public static String concatId(final AuthenticationId authenticationId) {
    return authenticationId.getAuthority() + ";" + authenticationId.getId();
  }

  @JsonPOJOBuilder(withPrefix = "")
  public static class RequestAccessBuilder {}
}
