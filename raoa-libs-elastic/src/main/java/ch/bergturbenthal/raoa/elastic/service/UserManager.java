package ch.bergturbenthal.raoa.elastic.service;

import ch.bergturbenthal.raoa.elastic.model.AuthenticationId;
import ch.bergturbenthal.raoa.elastic.model.Group;
import ch.bergturbenthal.raoa.elastic.model.RequestAccess;
import ch.bergturbenthal.raoa.elastic.model.User;
import java.util.UUID;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserManager {

  Mono<User> createNewUser(RequestAccess baseRequest);

  Mono<Group> createNewGroup(String groupName);

  Mono<Boolean> removeUser(UUID id);

  Mono<Boolean> removeGroup(UUID id);

  void assignNewIdentity(UUID existingId, AuthenticationId baseRequest);

  Flux<User> listUsers();

  Flux<Group> listGroups();

  Mono<User> loadUser(UUID userId);

  Mono<User> updateUser(UUID userId, Function<User, User> updater, final String updateDescription);

  Mono<Group> updateGroup(
      UUID groupId, Function<Group, Group> updater, final String updateDescription);
}
