package ch.bergturbenthal.raoa.viewer.model.graphql;

import ch.bergturbenthal.raoa.elastic.model.PersonalUserData;
import java.util.UUID;
import lombok.Value;

@Value
public class UserReference {
  public static final UUID UNKNOWN_USER_ID = UUID.randomUUID();
  UUID id;
  PersonalUserData info;
  QueryContext context;
}
