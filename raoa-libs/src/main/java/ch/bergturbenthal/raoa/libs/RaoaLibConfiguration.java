package ch.bergturbenthal.raoa.libs;

import ch.bergturbenthal.raoa.libs.properties.Properties;
import ch.bergturbenthal.raoa.libs.service.AsyncService;
import ch.bergturbenthal.raoa.libs.service.impl.BareGitAccess;
import ch.bergturbenthal.raoa.libs.service.impl.ExecutorAsyncService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

@Configuration
@EnableConfigurationProperties(Properties.class)
@ComponentScan(basePackageClasses = BareGitAccess.class)
@Slf4j
public class RaoaLibConfiguration {

  @Bean
  public AsyncService asyncService(final Properties properties) {
    return new ExecutorAsyncService(properties);
  }

  @Bean
  ScheduledExecutorService executorService() {
    return Executors.newScheduledThreadPool(
        10, new CustomizableThreadFactory("raoa-lib-scheduled"));
  }
}
