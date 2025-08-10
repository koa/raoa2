package ch.bergturbenthal.raoa.elastic.repository;

import ch.bergturbenthal.raoa.elastic.model.UploadedFile;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface UploadedFileRepository extends ReactiveCrudRepository<UploadedFile, UUID> {
}
