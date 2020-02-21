package ch.bergturbenthal.raoa.elastic.repository;

import ch.bergturbenthal.raoa.elastic.model.AccessRequest;
import ch.bergturbenthal.raoa.elastic.model.AuthenticationId;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface AccessRequestRepository extends ReactiveCrudRepository<AccessRequest, String> {

  Flux<AccessRequest> findByAuthenticationId(AuthenticationId id);
}
