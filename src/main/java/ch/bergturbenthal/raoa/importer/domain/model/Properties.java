package ch.bergturbenthal.raoa.importer.domain.model;

import java.nio.file.Path;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.lang.NonNull;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "raoa.import")
@Data
@Validated
public class Properties {
  @NonNull private Path media;
  @NonNull private Path repository;
}
