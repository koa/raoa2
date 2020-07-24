package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import java.util.List;
import lombok.Value;

@Value
public class CredentialUpgrade {
  List<SingleUserUpdate> userUpdates;
  List<SingleGroupUpdate> groupUpdates;
  List<SingleGroupMembershipUpdate> groupMembershipUpdates;
}
