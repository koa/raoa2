package ch.bergturbenthal.raoa.viewer.model.graphql;

import lombok.Builder;
import lombok.Value;

@Builder
@Value
public class AuthenticationInformation {
  private AuthenticationState state;
  private String name;
  private String email;
  private String picture;
}
