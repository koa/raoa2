package ch.bergturbenthal.raoa.importer.domain.model;

import java.nio.file.Path;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "raoa.import")
@Data
public class Properties {
  private Path media;
  private Path repository;
}
