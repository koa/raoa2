package ch.bergturbenthal.raoa.importer;

import ch.bergturbenthal.raoa.libs.properties.Properties;
import ch.bergturbenthal.raoa.libs.service.AsyncService;
import ch.bergturbenthal.raoa.libs.service.FileImporter;
import ch.bergturbenthal.raoa.libs.service.impl.BareAlbumList;
import ch.bergturbenthal.raoa.libs.service.impl.ConcurrencyLimiter;
import ch.bergturbenthal.raoa.libs.service.impl.ExecutorAsyncService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TestLoadGitDirs {
  public static void main(String[] args) throws IOException {
    final Properties properties = new Properties();
    properties.setRepository(Path.of("/media", "akoenig", "Transfer HD 8TB").toFile());

    final ConcurrencyLimiter limiter =
        new ConcurrencyLimiter(properties, new SimpleMeterRegistry());
    final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    final AsyncService asyncService = new ExecutorAsyncService();
    final BareAlbumList albumList = new BareAlbumList(properties, meterRegistry, asyncService);

    final Path dir = Path.of("/media/akoenig/NIKON D500/DCIM/198ND500");
    final FileImporter importer = albumList.createImporter();
    Collection<Path> importedFiles = new ArrayList<>();
    Files.list(dir)
        .forEach(
            f -> {
              if (importer.importFile(f).block()) {
                importedFiles.add(f);
              } else log.warn("Cannot load " + f);
              //                AutoDetectParser parser = new AutoDetectParser();
              //                BodyContentHandler handler = new BodyContentHandler();
              //                Metadata metadata = new Metadata();
              //                final TikaInputStream inputStream = TikaInputStream.get(f);
              //                parser.parse(inputStream, handler, metadata);
              //                final String filename = f.getFileName().toString();
              //                final Optional<Instant> createDate =
              //
              // Optional.ofNullable(metadata.getDate(TikaCoreProperties.CREATED))
              //                        .map(Date::toInstant);
              //                createDate.ifPresent(
              //                    c -> {
              //                      final String prefix =
              //                          DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
              //                              .format(c.atZone(ZoneId.systemDefault()));
              //                      log.info(prefix + "-" + filename);
              //                      log.info("Content-Type: " +
              // metadata.get(Metadata.CONTENT_TYPE));
              //                      log.info("Created     : " + c);
              //                      log.info("Orientation : " +
              // metadata.getInt(Metadata.ORIENTATION));
              //                      log.info("Album       : " + albumList.albumOf(c));
              //                    });
            });
    if (importer.commitAll().block()) {
      for (Path p : importedFiles) {
        Files.delete(p);
      }
    }
  }
}
