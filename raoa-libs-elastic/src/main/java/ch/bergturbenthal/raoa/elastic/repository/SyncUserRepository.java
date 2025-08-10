package ch.bergturbenthal.raoa.elastic.repository;

import ch.bergturbenthal.raoa.elastic.model.User;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface SyncUserRepository extends CrudRepository<User, UUID> {
}
