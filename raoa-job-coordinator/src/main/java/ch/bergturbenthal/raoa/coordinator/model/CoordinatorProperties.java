package ch.bergturbenthal.raoa.coordinator.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties(prefix = "raoa.coordinator")
@Data
public class CoordinatorProperties {
    private int concurrentProcessingImages = 10;
    private int concurrentProcessingAlbums = 2;
    private Duration processTimeout = Duration.ofMinutes(15);
    private String imageProcessorUrl = "discovery://image-processor";
    private String mediaProcessorTemplate;
    private Map<String, String> videoResource = Map.of("squat.ai/video", "1");
}
