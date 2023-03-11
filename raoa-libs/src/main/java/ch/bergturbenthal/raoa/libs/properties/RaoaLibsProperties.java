package ch.bergturbenthal.raoa.libs.properties;

import java.io.File;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "raoa")
@Data
// @Validated
@Slf4j
public class RaoaLibsProperties {
  static {
    log.info("Init properties class", new RuntimeException());
  }

  private File repository;
  private File thumbnailDir = new File("/tmp/raoa/thumbnails");
  private int maxConcurrent = 30;
  private int asyncThreadCount = 10;
  private String superuser = "107024483334418897627";

  public RaoaLibsProperties(
      File repository,
      @DefaultValue("/tmp/raoa/thumbnails") File thumbnailDir,
      @DefaultValue("30") int maxConcurrent,
      @DefaultValue("10") int asyncThreadCount,
      @DefaultValue("107024483334418897627") String superuser) {
    this.repository = repository;
    this.thumbnailDir = thumbnailDir;
    this.maxConcurrent = maxConcurrent;
    this.asyncThreadCount = asyncThreadCount;
    this.superuser = superuser;
    log.info("Created properties " + this, new RuntimeException());
  }
}
