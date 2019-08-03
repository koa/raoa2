package ch.bergturbenthal.raoa.viewer.model.graphql;

import lombok.Value;

@Value
public class AlbumEntry {
  private final Album album;
  private final String id;
  private final String name;
}
