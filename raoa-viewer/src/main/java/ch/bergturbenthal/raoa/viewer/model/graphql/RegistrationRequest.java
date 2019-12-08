package ch.bergturbenthal.raoa.viewer.model.graphql;

import ch.bergturbenthal.raoa.libs.model.elasticsearch.AuthenticationId;
import ch.bergturbenthal.raoa.libs.model.elasticsearch.PersonalUserData;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RegistrationRequest {
  private AuthenticationId authenticationId;
  private PersonalUserData data;
  private String reason;
  private Album requestAlbum;
}
