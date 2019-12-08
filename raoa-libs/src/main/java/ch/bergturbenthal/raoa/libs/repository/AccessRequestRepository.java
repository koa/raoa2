package ch.bergturbenthal.raoa.libs.repository;

import ch.bergturbenthal.raoa.libs.model.elasticsearch.AccessRequest;
import ch.bergturbenthal.raoa.libs.model.elasticsearch.AuthenticationId;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface AccessRequestRepository extends ReactiveCrudRepository<AccessRequest, String> {

  Flux<AccessRequest> findByAuthenticationId(AuthenticationId id);
}
