package ch.bergturbenthal.raoa.sync;

import ch.bergturbenthal.raoa.libs.RaoaLibConfiguration;
import ch.bergturbenthal.raoa.sync.config.SyncProperties;
import ch.bergturbenthal.raoa.sync.service.SyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

@Slf4j
@SpringBootApplication
@Import({RaoaLibConfiguration.class})
@EnableConfigurationProperties(SyncProperties.class)
public class App {
  public static void main(String[] args) {
    try (final ConfigurableApplicationContext run = SpringApplication.run(App.class, args)) {
      run.getBean(SyncService.class).sync();
    }
  }
}
