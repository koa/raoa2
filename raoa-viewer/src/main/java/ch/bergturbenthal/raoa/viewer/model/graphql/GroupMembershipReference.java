package ch.bergturbenthal.raoa.viewer.model.graphql;

import java.time.Instant;
import lombok.Value;

@Value
public class GroupMembershipReference {
  Instant from;
  Instant until;
  GroupReference group;
}
