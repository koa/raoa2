package ch.bergturbenthal.raoa.coordinator;

import ch.bergturbenthal.raoa.coordinator.model.CoordinatorProperties;
import ch.bergturbenthal.raoa.coordinator.service.impl.Poller;
import ch.bergturbenthal.raoa.elastic.RaoaElasticConfiguration;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.elastic.service.UserManager;
import ch.bergturbenthal.raoa.elastic.service.impl.InitAdminUserIfMissing;
import ch.bergturbenthal.raoa.libs.properties.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.elasticsearch.ElasticSearchRestHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {ElasticSearchRestHealthContributorAutoConfiguration.class})
@EnableConfigurationProperties(CoordinatorProperties.class)
@Import({RaoaElasticConfiguration.class})
@ComponentScan(basePackageClasses = {Poller.class})
@EnableScheduling
@Slf4j
public class RaoaJobCoordinator {
  public static void main(String[] args) {
    SpringApplication.run(RaoaJobCoordinator.class, args);
  }

  @Bean
  public InitAdminUserIfMissing initAdminUserIfMissing(
      final Properties properties,
      final DataViewService dataViewService,
      final UserManager userManager) {
    return new InitAdminUserIfMissing(properties, dataViewService, userManager);
  }
}
