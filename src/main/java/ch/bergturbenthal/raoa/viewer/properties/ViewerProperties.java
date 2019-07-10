package ch.bergturbenthal.raoa.viewer.properties;

import java.io.File;
import java.io.IOException;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "raoa.viewer")
@Data
public class ViewerProperties {
  private File cacheDir;
  private String thumbnailerService = "raoa-thumbnailer";

  public ViewerProperties() throws IOException {
    cacheDir = File.createTempFile("cache", "tmp");
    cacheDir.delete();
  }
}
