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
  private File dataDir;
  private String thumbnailerService = "raoa-thumbnailer";
  private int concurrentThumbnailers = 40;
  private DataSize defaultCacheSize = DataSize.ofMegabytes(100);
  private Map<String, DataSize> cacheSize = Collections.emptyMap();
  private boolean enableAuthentication = true;
  private ClientProperties clientProperties = new ClientProperties();
  private boolean alwaysShowLatestRepository = false;
  private boolean allowAlsoDebugging = false;
  private boolean newUi = false;

  public ViewerProperties() throws IOException {

    dataDir = File.createTempFile("data", "tmp");
    dataDir.delete();
  }

  @Data
  public static class ClientProperties {
    private String googleClientId;
  }
}
