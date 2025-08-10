package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import java.util.UUID;
import lombok.Value;

@Value
public class SingleGroupUpdate {
    UUID albumId;
    UUID groupId;
    boolean isMember;
}
