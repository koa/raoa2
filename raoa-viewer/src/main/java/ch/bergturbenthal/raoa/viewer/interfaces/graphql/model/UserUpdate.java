package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import java.util.List;
import java.util.Set;
import lombok.Value;

@Value
public class UserUpdate {
  Boolean canManageUsers;
  List<UserVisibilityUpdate> visibilityUpdates;
  Set<String> removeLabels;
  Boolean isEditor;
}
