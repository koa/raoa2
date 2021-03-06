package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import java.time.Instant;
import java.util.UUID;
import lombok.Value;

@Value
public class SingleGroupMembershipUpdate {
  UUID groupId;
  UUID userId;
  Instant from;
  Instant until;
  boolean isMember;
}
