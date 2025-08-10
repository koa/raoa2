package ch.bergturbenthal.raoa.viewer.model.graphql;

import java.time.Instant;
import lombok.Value;

@Value
public class UserMembershipReference {
    Instant from;
    Instant until;
    UserReference user;
}
