package ch.bergturbenthal.raoa.libs.service;

import ch.bergturbenthal.raoa.libs.model.elasticsearch.AccessRequest;
import ch.bergturbenthal.raoa.libs.model.elasticsearch.AuthenticationId;
import ch.bergturbenthal.raoa.libs.model.elasticsearch.User;
import java.util.UUID;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserManager {

  Mono<User> createNewUser(AccessRequest baseRequest);

  Mono<Boolean> removeUser(UUID id);

  void assignNewIdentity(UUID existingId, AuthenticationId baseRequest);

  Flux<User> listUsers();

  Mono<User> loadUser(UUID userId);

  Mono<User> updateUser(UUID userId, Function<User, User> updater, final String updateDescription);
}
