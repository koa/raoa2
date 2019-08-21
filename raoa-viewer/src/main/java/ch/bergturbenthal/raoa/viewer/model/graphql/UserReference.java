package ch.bergturbenthal.raoa.viewer.model.graphql;

import ch.bergturbenthal.raoa.viewer.model.usermanager.PersonalUserData;
import java.util.UUID;
import lombok.Value;

@Value
public class UserReference {
  public static final UUID UNKNOWN_USER_ID = UUID.randomUUID();
  private UUID id;
  private PersonalUserData info;
  private QueryContext context;
}
