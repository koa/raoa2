package ch.bergturbenthal.raoa.viewer.model.graphql;

import ch.bergturbenthal.raoa.elastic.model.AlbumData;
import java.util.UUID;
import lombok.Value;
import reactor.core.publisher.Mono;

@Value
public class Album {
  UUID id;
  QueryContext context;
  Mono<AlbumData> elAlbumData;
}
