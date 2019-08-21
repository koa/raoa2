package ch.bergturbenthal.raoa.viewer.model.graphql;

import java.util.UUID;
import lombok.Value;

@Value
public class Album {
  private UUID id;
  private QueryContext context;
}
