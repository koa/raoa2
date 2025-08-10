package ch.bergturbenthal.raoa.coordinator.model;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "raoa.coordinator")
@Data
public class CoordinatorProperties {
    private int concurrentProcessingImages = 10;
    private int concurrentProcessingAlbums = 2;
    private Duration processTimeout = Duration.ofMinutes(15);
    private String imageProcessorUrl = "discovery://image-processor";
    private String mediaProcessorTemplate;
}
