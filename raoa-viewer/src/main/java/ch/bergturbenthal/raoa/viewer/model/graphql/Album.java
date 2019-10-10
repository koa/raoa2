package ch.bergturbenthal.raoa.viewer.model.graphql;

import ch.bergturbenthal.raoa.viewer.model.elasticsearch.AlbumData;
import java.util.UUID;
import lombok.Value;
import reactor.core.publisher.Mono;

@Value
public class Album {
  private UUID id;
  private QueryContext context;
  private Mono<AlbumData> elAlbumData;
}
