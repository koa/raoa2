package ch.bergturbenthal.raoa.importer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

@Slf4j
public class TestTikal {
  public static void main(String[] args) throws IOException {
    final Path dir = Path.of("/media/akoenig/NIKON D500/DCIM/198ND500");
    Files.list(dir)
        .forEach(
            f -> {
              try {
                AutoDetectParser parser = new AutoDetectParser();
                BodyContentHandler handler = new BodyContentHandler();
                Metadata metadata = new Metadata();
                final TikaInputStream inputStream = TikaInputStream.get(f);
                parser.parse(inputStream, handler, metadata);
                final String filename = f.getFileName().toString();
                final Optional<Instant> createDate =
                    Optional.ofNullable(metadata.getDate(TikaCoreProperties.CREATED))
                        .map(Date::toInstant);
                createDate.ifPresent(
                    c -> {
                      final String prefix =
                          DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
                              .format(c.atZone(ZoneId.systemDefault()));
                      log.info(prefix + "-" + filename);
                      log.info("Content-Type: " + metadata.get(Metadata.CONTENT_TYPE));
                      log.info("Created     : " + c);
                      log.info("Orientation : " + metadata.getInt(Metadata.ORIENTATION));
                    });
              } catch (TikaException | IOException | SAXException e) {
                log.error("Cannot parse file " + f, e);
              }
            });
  }
}
