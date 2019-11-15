package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import java.util.UUID;
import lombok.Value;

@Value
public class UserVisibilityUpdate {
  private UUID albumId;
  private boolean visibility;
}
