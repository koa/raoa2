package ch.bergturbenthal.raoa.libs.properties;

import java.io.File;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "raoa")
@Data
// @Validated
public class Properties {
  // @NonNull
  private File media;
  // @NonNull
  private File repository;
}
