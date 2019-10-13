package ch.bergturbenthal.raoa.viewer.repository;

import ch.bergturbenthal.raoa.viewer.model.usermanager.AccessRequest;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AuthenticationId;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface AccessRequestRepository extends ReactiveCrudRepository<AccessRequest, UUID> {

  Flux<AccessRequest> findByAuthenticationId(AuthenticationId id);
}
