package ch.bergturbenthal.raoa.elastic.service;

import ch.bergturbenthal.raoa.elastic.model.*;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import com.adobe.xmp.XMPMeta;
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

  Flux<User> findUserForAuthentication(AuthenticationId authenticationId);

  Mono<User> findUserById(UUID id);

  Mono<Group> findGroupById(UUID id);

  Flux<User> listUserForAlbum(UUID albumId);

  Mono<RequestAccess> getPendingRequest(AuthenticationId id);

  Mono<RequestAccess> requestAccess(RequestAccess request);

  Flux<RequestAccess> listAllRequestedAccess();

  Mono<Void> removePendingAccessRequest(AuthenticationId id);

  Flux<User> listUsers();

  Flux<Group> listGroups();

  Mono<AlbumEntryData> updateKeyword(UUID albumId, ObjectId entryId, XMPMeta xmpMeta);
}
