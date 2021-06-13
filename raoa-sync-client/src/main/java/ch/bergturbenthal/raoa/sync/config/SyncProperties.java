package ch.bergturbenthal.raoa.sync.config;

import java.net.URI;
import lombok.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

@ConfigurationProperties(prefix = "raoa")
@ConstructorBinding
@Value
public class SyncProperties {
  URI uri;
  String username;
  String password;
  String repository;
}
