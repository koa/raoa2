package ch.bergturbenthal.raoa.elastic.repository;

import ch.bergturbenthal.raoa.elastic.model.TemporaryPassword;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TemporaryPasswordRepository extends ReactiveCrudRepository<TemporaryPassword, String> {
    Mono<TemporaryPassword> findByUserIdAndPassword(UUID userId, String password);

    Flux<TemporaryPassword> findByUserId(UUID userId);
}
