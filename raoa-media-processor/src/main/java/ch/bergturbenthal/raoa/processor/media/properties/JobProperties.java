package ch.bergturbenthal.raoa.processor.media.properties;

import java.util.Set;
import java.util.UUID;
import lombok.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("raoa.job")
@Value
public class JobProperties {
  UUID repository;
  Set<String> files;

  @ConstructorBinding
  public JobProperties(UUID repository, Set<String> files) {
    this.repository = repository;
    this.files = files;
  }
}
