package ch.bergturbenthal.raoa.elastic.repository;

import ch.bergturbenthal.raoa.elastic.model.CommitJob;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface CommitJobRepository extends ReactiveCrudRepository<CommitJob, UUID> {
    Flux<CommitJob> findByCurrentPhase(final CommitJob.State currentPhase);
}
