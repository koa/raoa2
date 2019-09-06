package ch.bergturbenthal.raoa.viewer.properties;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "raoa.viewer")
@Data
public class ViewerProperties {
  private File cacheDir;
  private File dataDir;
  private String thumbnailerService = "raoa-thumbnailer";
  private int concurrentThumbnailers = 40;
  private DataSize defaultCacheSize = DataSize.ofMegabytes(100);
  private Map<String, DataSize> cacheSize = Collections.emptyMap();
  private boolean enableAuthentication = true;
  private String superuser = "107024483334418897627";
  private ClientProperties clientProperties = new ClientProperties();

  @Data
  public static class ClientProperties {
    private String googleClientId;
  }

  public ViewerProperties() throws IOException {
    cacheDir = File.createTempFile("cache", "tmp");
    cacheDir.delete();
    dataDir = File.createTempFile("data", "tmp");
    dataDir.delete();
  }
}
