package ch.bergturbenthal.raoa.viewer.model.graphql;

import ch.bergturbenthal.raoa.libs.model.elasticsearch.AlbumEntryData;
import lombok.Value;
import reactor.core.publisher.Mono;

@Value
public class AlbumEntry {
  private final Album album;
  private final String id;
  private final String name;
  private final Mono<AlbumEntryData> elDataEntry;
}
