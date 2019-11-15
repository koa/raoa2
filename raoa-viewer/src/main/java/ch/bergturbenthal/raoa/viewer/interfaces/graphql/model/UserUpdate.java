package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import java.util.List;
import lombok.Value;

@Value
public class UserUpdate {
  private Boolean canManageUsers;
  private List<UserVisibilityUpdate> visibilityUpdates;
}
