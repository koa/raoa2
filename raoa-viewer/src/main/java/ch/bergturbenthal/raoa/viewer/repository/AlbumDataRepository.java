package ch.bergturbenthal.raoa.viewer.repository;

import ch.bergturbenthal.raoa.viewer.model.elasticsearch.AlbumData;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface AlbumDataRepository extends ReactiveCrudRepository<AlbumData, UUID> {}
