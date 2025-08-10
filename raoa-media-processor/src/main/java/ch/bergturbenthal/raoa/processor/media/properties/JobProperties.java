package ch.bergturbenthal.raoa.processor.media.properties;

import lombok.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;
import java.util.UUID;

@ConfigurationProperties("raoa.job")
@Value
public class JobProperties {
    UUID repository;
    Set<String> files;
}
