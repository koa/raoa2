package ch.bergturbenthal.raoa.elastic.service;

import ch.bergturbenthal.raoa.elastic.model.AuthenticationId;
import ch.bergturbenthal.raoa.elastic.model.Group;
import ch.bergturbenthal.raoa.elastic.model.RequestAccess;
import ch.bergturbenthal.raoa.elastic.model.User;
import ch.bergturbenthal.raoa.libs.service.Updater;
import java.util.UUID;
import java.util.function.Function;
import org.eclipse.jgit.lib.ObjectId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserManager {

  Mono<User> createNewUser(RequestAccess baseRequest, final Updater.CommitContext context);

  Mono<Group> createNewGroup(String groupName, final Updater.CommitContext context);

  Mono<ObjectId> getMetaVersion();

  Mono<Boolean> removeUser(UUID id, final Updater.CommitContext context);

  void assignNewIdentity(
      UUID existingId, AuthenticationId baseRequest, final Updater.CommitContext context);

  Flux<User> listUsers();

  Flux<Group> listGroups();

  Mono<User> loadUser(UUID userId);

  Mono<User> context(
      UUID userId, Function<User, User> updater, final Updater.CommitContext updateDescription);

  Mono<Group> updateGroup(
      UUID groupId, Function<Group, Group> updater, final Updater.CommitContext updateDescription);
}
