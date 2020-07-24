package ch.bergturbenthal.raoa.elastic.repository;

import ch.bergturbenthal.raoa.elastic.model.Group;
import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface GroupRepository extends ReactiveCrudRepository<Group, UUID> {}
