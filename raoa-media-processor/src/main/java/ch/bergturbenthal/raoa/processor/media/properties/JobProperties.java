package ch.bergturbenthal.raoa.processor.media.properties;

import java.util.Set;
import java.util.UUID;
import lombok.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

@ConstructorBinding
@ConfigurationProperties("raoa.job")
@Value
public class JobProperties {
    UUID repository;
    Set<String> files;
}
