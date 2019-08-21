package ch.bergturbenthal.raoa.importer.domain.model;

import java.io.File;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "raoa.import")
@Data
@Validated
public class Properties {
  // @NonNull
  private File media;
}
