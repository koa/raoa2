package ch.bergturbenthal.raoa.libs.repository;

import ch.bergturbenthal.raoa.libs.model.elasticsearch.User;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface SyncUserRepository extends CrudRepository<User, UUID> {}
