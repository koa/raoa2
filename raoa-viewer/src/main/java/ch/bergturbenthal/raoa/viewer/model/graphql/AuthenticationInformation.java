package ch.bergturbenthal.raoa.viewer.model.graphql;

import lombok.Builder;
import lombok.Value;

@Builder
@Value
public class AuthenticationInformation {
  private AuthenticationState state;
  private UserReference user;
}
