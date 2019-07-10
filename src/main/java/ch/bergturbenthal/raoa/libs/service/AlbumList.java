package ch.bergturbenthal.raoa.libs.service;

import java.util.stream.Stream;
import lombok.Value;

public interface AlbumList {

  FileImporter createImporter();

  Stream<FoundAlbum> listAlbums();

  @Value
  class FoundAlbum {
    private String name;
    private GitAccess access;
  }
}
