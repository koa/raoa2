package ch.bergturbenthal.raoa.elastic.service;

import ch.bergturbenthal.raoa.elastic.model.AlbumData;
import ch.bergturbenthal.raoa.elastic.model.AlbumEntryData;
import ch.bergturbenthal.raoa.elastic.model.AuthenticationId;
import ch.bergturbenthal.raoa.elastic.model.Group;
import ch.bergturbenthal.raoa.elastic.model.RequestAccess;
import ch.bergturbenthal.raoa.elastic.model.TemporaryPassword;
import ch.bergturbenthal.raoa.elastic.model.User;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import com.adobe.internal.xmp.XMPMeta;
import java.time.Instant;
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

  Mono<TemporaryPassword> createTemporaryPassword(
      UUID user, final String title, String password, Instant validUntil);

  Mono<User> findAndValidateTemporaryPassword(UUID user, String password);

  Flux<TemporaryPassword> findTemporaryPasswordsByUser(UUID user);

  Mono<Void> deleteTemporaryPasswordsByUser(UUID userId, String title);
}
