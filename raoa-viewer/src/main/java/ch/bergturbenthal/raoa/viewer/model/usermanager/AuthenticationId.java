package ch.bergturbenthal.raoa.viewer.model.usermanager;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AuthenticationId {
  private String authority;
  private String id;
}
