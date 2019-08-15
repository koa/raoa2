package ch.bergturbenthal.raoa.viewer.model.usermanager;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AccessRequest {
  private PersonalUserData userData;
  private AuthenticationId authenticationId;
  private String comment;
  private Instant requestTime;
  private UUID requestedAlbum;
}
