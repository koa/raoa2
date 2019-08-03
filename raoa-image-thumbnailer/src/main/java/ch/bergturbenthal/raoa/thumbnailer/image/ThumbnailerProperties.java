package ch.bergturbenthal.raoa.thumbnailer.image;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "thumbnailer")
public class ThumbnailerProperties {
  private int concurrent = 4;
}
