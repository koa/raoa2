package ch.bergturbenthal.raoa.coordinator.model;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "raoa.coordinator")
@Data
public class CoordinatorProperties {
  private int concurrentProcessingImages = 100;
  private int concurrentProcessingAlbums = 6;
  private Duration processTimeout = Duration.ofMinutes(5);
}
