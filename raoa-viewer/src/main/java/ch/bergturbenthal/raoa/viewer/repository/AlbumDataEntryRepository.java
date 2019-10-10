package ch.bergturbenthal.raoa.viewer.repository;

import ch.bergturbenthal.raoa.viewer.model.elasticsearch.AlbumEntryData;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface AlbumDataEntryRepository extends ReactiveCrudRepository<AlbumEntryData, String> {
  Flux<AlbumEntryData> findByAlbumId(UUID albumId);
}
