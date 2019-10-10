package ch.bergturbenthal.raoa.viewer.service;

import ch.bergturbenthal.raoa.viewer.model.elasticsearch.AlbumData;
import ch.bergturbenthal.raoa.viewer.model.elasticsearch.AlbumEntryData;
import java.util.UUID;
import org.eclipse.jgit.lib.ObjectId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ImageDataService {
  Flux<AlbumData> listAlbums();

  Mono<AlbumData> readAlbum(UUID id);

  Flux<AlbumEntryData> listEntries(UUID id);

  Mono<AlbumEntryData> loadEntry(UUID albumId, ObjectId entriId);
}
