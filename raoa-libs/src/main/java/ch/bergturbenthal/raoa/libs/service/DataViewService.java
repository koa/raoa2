package ch.bergturbenthal.raoa.libs.service;

import ch.bergturbenthal.raoa.libs.model.elasticsearch.*;
import java.util.UUID;
import org.eclipse.jgit.lib.ObjectId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface DataViewService {
  Mono<Void> updateUserData();

  Mono<Long> updateAlbums(Flux<AlbumList.FoundAlbum> albumList);

  Flux<AlbumData> listAlbums();

  Mono<AlbumData> readAlbum(UUID id);

  Flux<AlbumEntryData> listEntries(UUID id);

  Mono<AlbumEntryData> loadEntry(UUID albumId, ObjectId entriId);

  Mono<User> findUserForAuthentication(AuthenticationId authenticationId);

  Mono<User> findUserById(UUID id);

  Flux<User> listUserForAlbum(UUID albumId);

  Mono<AccessRequest> getPendingRequest(AuthenticationId id);

  Mono<AccessRequest> requestAccess(AccessRequest request);

  Flux<AccessRequest> listAllRequestedAccess();

  Mono<Void> removePendingAccessRequest(AuthenticationId id);

  Flux<User> listUsers();
}
