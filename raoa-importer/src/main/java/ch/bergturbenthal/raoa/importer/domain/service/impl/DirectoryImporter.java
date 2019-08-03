package ch.bergturbenthal.raoa.importer.domain.service.impl;

import ch.bergturbenthal.raoa.importer.domain.service.Importer;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.FileImporter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DirectoryImporter implements Importer {
  private final AlbumList albumList;

  public DirectoryImporter(final AlbumList albumList) {
    this.albumList = albumList;
  }

  private static String humanReadableByteCount(long bytes, boolean si) {
    int unit = si ? 1000 : 1024;
    if (bytes < unit) return bytes + " B";
    int exp = (int) (Math.log(bytes) / Math.log(unit));
    String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
    return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
  }

  @Override
  public void importDirectories(final Path src) throws IOException {
    Instant startTime = Instant.now();
    final FileImporter importer = albumList.createImporter();
    final Optional<List<Path>> result =
            Flux.fromIterable(Files.list(src).collect(Collectors.toList()))
                .flatMap(
                        mountpoint1 -> {
                          try {
                            final Path dcim1 = mountpoint1.resolve("DCIM");
                            if (!Files.exists(dcim1)) return Flux.empty();
                            return Flux.fromIterable(Files.list(dcim1).collect(Collectors.toList()));
                          } catch (IOException e1) {
                            throw new RuntimeException("Cannot process mountpoint " + mountpoint1, e1);
                          }
                        },
                        2)
                .flatMap(
                        imageDir1 -> {
                          try {
                            if (Files.isDirectory(imageDir1))
                              return Flux.fromIterable(Files.list(imageDir1).collect(Collectors.toList()));
                            else return Flux.empty();
                          } catch (IOException e1) {
                            throw new RuntimeException("Cannot list directory " + imageDir1, e1);
                          }
                        },
                        2)
                .flatMap(
                        mediaFile1 ->
                                importer
                                        .importFile(mediaFile1)
                                        .doOnNext(
                                                ok -> {
                                                  if (!ok) {
                                                    log.warn("Cannot load " + mediaFile1);
                                                  }
                                                })
                                        .filter(ok -> ok)
                                        .map(r -> mediaFile1),
                        5)
                .collectList()
                .flatMap(
                        t ->
                                importer
                                        .commitAll()
                                        .doOnNext(
                                                ok -> {
                                                  if (!ok) {
                                                    log.warn("Cannot commit files");
                                                  }
                                                })
                                        .filter(ok -> ok)
                                        .map(ok -> t))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .block();
    result.ifPresent(
            importedFiles -> {
              try {
                long totalSize = 0;
                for (Path p : importedFiles) {
                  totalSize += Files.size(p);
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
                                + humanReadableByteCount(totalSize, false)
                                + ") in "
                                + durationDescription);
              } catch (IOException e) {
                log.warn("Cannot close import", e);
              }
            });
  }
}
