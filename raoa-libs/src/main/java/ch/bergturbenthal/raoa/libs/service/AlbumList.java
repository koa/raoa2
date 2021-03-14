package ch.bergturbenthal.raoa.libs.service;

import java.util.UUID;
import lombok.Value;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AlbumList {

  void resetCache();

  FileImporter createImporter();

  Flux<FoundAlbum> listAlbums();

  Mono<GitAccess> getAlbum(UUID albumId);

  @Value
  class FoundAlbum {
    UUID albumId;
    GitAccess access;
  }
}
