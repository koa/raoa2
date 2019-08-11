package ch.bergturbenthal.raoa.viewer.model.usermanager;

import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class PersonalUserData {
  private String name;
  private String picture;
  private String comment;
  private String email;
  private boolean emailVerified;
}
