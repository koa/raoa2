package ch.bergturbenthal.raoa.viewer.repository;

import ch.bergturbenthal.raoa.viewer.model.elasticsearch.AlbumData;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface AlbumDataRepository extends ReactiveCrudRepository<AlbumData, UUID> {
  Flux<AlbumData> findByEntryCountGreaterThan(int count);
}
