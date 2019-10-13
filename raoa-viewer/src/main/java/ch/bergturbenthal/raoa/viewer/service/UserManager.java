package ch.bergturbenthal.raoa.viewer.service;

import ch.bergturbenthal.raoa.viewer.model.usermanager.AccessRequest;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AuthenticationId;
import ch.bergturbenthal.raoa.viewer.model.usermanager.User;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserManager {

  Mono<User> createNewUser(AccessRequest baseRequest);

  void assignNewIdentity(UUID existingId, AuthenticationId baseRequest);

  Flux<User> listUsers();
}
