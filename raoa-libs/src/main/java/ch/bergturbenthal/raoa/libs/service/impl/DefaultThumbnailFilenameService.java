package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.properties.Properties;
import ch.bergturbenthal.raoa.libs.service.ThumbnailFilenameService;
import java.io.File;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.stereotype.Service;

@Service
public class DefaultThumbnailFilenameService implements ThumbnailFilenameService {
  public static int[] SCALES = {25, 50, 100, 200, 400, 800, 1600};
  private final Properties properties;

  public DefaultThumbnailFilenameService(final Properties properties) {
    this.properties = properties;
  }

  @Override
  public File findThumbnailOf(final UUID album, final ObjectId entry, final int size) {
    for (int candidateSize : SCALES) {
      if (candidateSize >= size) return createThumbnailFile(album, entry, candidateSize);
    }
    return createThumbnailFile(album, entry, 1600);
  }

  @Override
  public Stream<FileAndScale> listThumbnailsOf(final UUID album, final ObjectId entry) {
    return Arrays.stream(SCALES)
        .mapToObj(size -> new FileAndScale(createThumbnailFile(album, entry, size), size));
  }

  private File createThumbnailFile(final UUID album, final ObjectId entryId, final int size) {

    final String name = entryId.name();
    final String prefix = name.substring(0, 2);
    final String suffix = name.substring(2);
    final String targetFilename =
        album.toString() + "/" + size + "/" + prefix + "/" + suffix + ".jpg";
    return new File(properties.getThumbnailDir(), targetFilename);
  }
}
