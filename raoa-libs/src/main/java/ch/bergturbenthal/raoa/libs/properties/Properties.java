package ch.bergturbenthal.raoa.libs.properties;

import java.io.File;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "raoa")
@Data
// @Validated
public class Properties {
  private File repository;
  private File thumbnailDir = new File("/tmp/raoa/thumbnails");
  private int maxConcurrent = 30;
}
