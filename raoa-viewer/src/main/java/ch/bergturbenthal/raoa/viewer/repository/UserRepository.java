package ch.bergturbenthal.raoa.viewer.repository;

import ch.bergturbenthal.raoa.viewer.model.usermanager.User;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface UserRepository extends ReactiveCrudRepository<User, UUID> {
  Flux<User> findByAuthenticationsAuthorityAndAuthenticationsId(String authority, String id);

  Flux<User> findByVisibleAlbums(UUID albumId);

  Flux<User> findBySuperuser(boolean isSuperuser);
}
