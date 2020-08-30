package ch.bergturbenthal.raoa.elastic.repository;

import ch.bergturbenthal.raoa.elastic.model.AuthenticationId;
import ch.bergturbenthal.raoa.elastic.model.RequestAccess;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface AccessRequestRepository extends ReactiveCrudRepository<RequestAccess, String> {

  Flux<RequestAccess> findByAuthenticationId(AuthenticationId id);
}
