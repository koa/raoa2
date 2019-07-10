package ch.bergturbenthal.raoa.libs.service;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.Value;

public interface AlbumList {

  FileImporter createImporter();

  Stream<FoundAlbum> listAlbums();

  Optional<GitAccess> getAlbum(UUID albumId);

  @Value
  class FoundAlbum {
    private UUID albumId;
    private GitAccess access;
  }
}
