package ch.bergturbenthal.raoa.viewer.model.graphql;

import ch.bergturbenthal.raoa.elastic.model.AlbumEntryData;
import lombok.Value;

@Value
public class AlbumEntry {
  Album album;
  String id;
  String name;
  AlbumEntryData elDataEntry;
}
