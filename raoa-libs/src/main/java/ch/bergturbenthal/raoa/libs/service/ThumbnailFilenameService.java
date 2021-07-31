package ch.bergturbenthal.raoa.libs.service;

import java.io.File;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.Value;
import org.eclipse.jgit.lib.ObjectId;

public interface ThumbnailFilenameService {
  File findThumbnailOf(UUID album, ObjectId entry, int size);

  Stream<FileAndScale> listThumbnailsOf(UUID album, ObjectId entry);

  @Value
  class FileAndScale {
    File file;
    File videoFile;
    int size;
  }
}
