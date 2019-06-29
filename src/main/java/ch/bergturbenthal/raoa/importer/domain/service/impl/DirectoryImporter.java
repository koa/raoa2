package ch.bergturbenthal.raoa.importer.domain.service.impl;

import ch.bergturbenthal.raoa.importer.domain.service.FileImporter;
import ch.bergturbenthal.raoa.importer.domain.service.Importer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DirectoryImporter implements Importer {

  private static String humanReadableByteCount(long bytes, boolean si) {
    int unit = si ? 1000 : 1024;
    if (bytes < unit) return bytes + " B";
    int exp = (int) (Math.log(bytes) / Math.log(unit));
    String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
    return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
  }

  @Override
  public void importDirectories(final Path src, final Path target) throws IOException {
    Instant startTime = Instant.now();
    final BareAlbumList albumList = new BareAlbumList(target);
    final FileImporter importer = albumList.createImporter();
    Collection<Path> importedFiles = new ArrayList<>();
    LongAdder totalSize = new LongAdder();
    Files.list(src)
        .flatMap(
            mountpoint -> {
              try {
                final Path dcim = mountpoint.resolve("DCIM");
                if (!Files.exists(dcim)) return Stream.empty();
                return Files.list(dcim);
              } catch (IOException e) {
                throw new RuntimeException("Cannot process mountpoint " + mountpoint, e);
              }
            })
        .flatMap(
            imageDir -> {
              try {
                return Files.list(imageDir);
              } catch (IOException e) {
                throw new RuntimeException("Cannot list directory " + imageDir, e);
              }
            })
        .forEach(
            mediaFile -> {
              try {
                if (importer.importFile(mediaFile)) {
                  importedFiles.add(mediaFile);
                  totalSize.add(Files.size(mediaFile));
                } else log.warn("Cannot load " + mediaFile);

              } catch (IOException e) {
                log.error("Cannot parse file " + mediaFile, e);
              }
            });
    if (importer.commitAll()) {
      for (Path p : importedFiles) {
        Files.delete(p);
      }
      final Duration importDuration = Duration.between(startTime, Instant.now());
      final long seconds = importDuration.getSeconds();
      String durationDescription =
          (seconds > 100) ? (seconds / 60 + "m, " + seconds % 60 + "s") : (seconds + "s");
      log.info(
          "Imported "
              + importedFiles.size()
              + " Files ("
              + humanReadableByteCount(totalSize.longValue(), false)
              + ") in "
              + durationDescription);
    }
  }
}
