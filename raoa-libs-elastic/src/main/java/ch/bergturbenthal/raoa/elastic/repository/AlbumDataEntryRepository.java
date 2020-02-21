package ch.bergturbenthal.raoa.elastic.repository;

import ch.bergturbenthal.raoa.elastic.model.AlbumEntryData;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface AlbumDataEntryRepository extends ReactiveCrudRepository<AlbumEntryData, String> {
  Flux<AlbumEntryData> findByAlbumId(UUID albumId);
}
