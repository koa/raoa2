package ch.bergturbenthal.raoa.libs.service;

import java.util.List;
import java.util.UUID;
import lombok.Value;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AlbumList {

  void resetCache();

  FileImporter createImporter();

  Flux<FoundAlbum> listAlbums();

  Flux<String> listParentDirs();

  Mono<GitAccess> getAlbum(UUID albumId);

  Mono<UUID> createAlbum(List<String> albumPath);

  @Value
  class FoundAlbum {
    UUID albumId;
    GitAccess access;
  }
}
