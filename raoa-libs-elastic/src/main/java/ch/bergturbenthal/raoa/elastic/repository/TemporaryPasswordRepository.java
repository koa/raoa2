package ch.bergturbenthal.raoa.elastic.repository;

import ch.bergturbenthal.raoa.elastic.model.TemporaryPassword;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface TemporaryPasswordRepository
    extends ReactiveCrudRepository<TemporaryPassword, UUID> {}
