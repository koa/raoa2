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
  private int asyncThreadCount = 10;
  private String superuser = "107024483334418897627";
}
