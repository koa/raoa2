package ch.bergturbenthal.raoa.viewer.model.usermanager;

import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class User {
  private UUID id;
  private PersonalUserData userData;
  private Set<UUID> visibleAlbums;
  private Set<AuthenticationId> authentications;
  private boolean superuser;
}
