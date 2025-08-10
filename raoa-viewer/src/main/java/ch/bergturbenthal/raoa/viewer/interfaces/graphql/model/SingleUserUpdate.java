package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import java.util.UUID;
import lombok.Value;

@Value
public class SingleUserUpdate {
    UUID userId;
    UUID albumId;
    boolean isMember;
}
