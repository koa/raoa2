package ch.bergturbenthal.raoa.viewer.service;

import ch.bergturbenthal.raoa.viewer.model.usermanager.AccessRequest;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AuthenticationId;
import ch.bergturbenthal.raoa.viewer.model.usermanager.User;
import java.util.Collection;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface UserManager {
  void requestAccess(AccessRequest request);

  Mono<Boolean> createNewUser(AuthenticationId baseRequest);

  void assignNewIdentity(UUID existingId, AuthenticationId baseRequest);

  Mono<User> findUserForAuthentication(AuthenticationId authenticationId);

  Mono<User> findUserById(UUID id);

  Collection<User> listUsers();

  Collection<User> listUserForAlbum(UUID albumId);

  Collection<AccessRequest> listPendingRequests();

  boolean hasPendingRequest(AuthenticationId id);
}
