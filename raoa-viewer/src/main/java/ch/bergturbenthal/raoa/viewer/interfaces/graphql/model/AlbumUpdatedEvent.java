package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import ch.bergturbenthal.raoa.viewer.model.graphql.Album;
import java.util.UUID;
import lombok.Value;

@Value
public class AlbumUpdatedEvent {
  AlbumUpdateEventType type;
  UUID id;
  Album album;

  public enum AlbumUpdateEventType {
    ADDED,
    REMOVED,
    MODIFIED
  }
}
