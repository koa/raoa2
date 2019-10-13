package ch.bergturbenthal.raoa.viewer.service;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.viewer.model.elasticsearch.AlbumData;
import ch.bergturbenthal.raoa.viewer.model.elasticsearch.AlbumEntryData;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AccessRequest;
import ch.bergturbenthal.raoa.viewer.model.usermanager.AuthenticationId;
import ch.bergturbenthal.raoa.viewer.model.usermanager.User;
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

  Mono<Void> removePendingAccessRequest(AccessRequest request);
}
