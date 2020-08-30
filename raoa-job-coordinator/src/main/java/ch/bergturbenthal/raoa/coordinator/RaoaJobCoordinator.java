package ch.bergturbenthal.raoa.coordinator;

import ch.bergturbenthal.raoa.coordinator.model.CoordinatorProperties;
import ch.bergturbenthal.raoa.coordinator.resolver.ServiceDiscoveryNameResolver;
import ch.bergturbenthal.raoa.coordinator.service.impl.Poller;
import ch.bergturbenthal.raoa.elastic.RaoaElasticConfiguration;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.elastic.service.UserManager;
import ch.bergturbenthal.raoa.elastic.service.impl.InitAdminUserIfMissing;
import ch.bergturbenthal.raoa.libs.properties.Properties;
import ch.bergturbenthal.raoa.processing.grpc.ProcessImageServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import java.net.URI;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.elasticsearch.ElasticSearchRestHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.DiscoveryClient;
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
  public ProcessImageServiceGrpc.ProcessImageServiceStub processImageServiceStub(
      final DiscoveryClient discoveryClient,
      final ScheduledExecutorService scheduler,
      CoordinatorProperties coordinatorProperties) {
    final String imageProcessorUrl = coordinatorProperties.getImageProcessorUrl();

    final ManagedChannelBuilder<?> builder =
        ManagedChannelBuilder.forTarget(imageProcessorUrl)
            .defaultLoadBalancingPolicy("round_robin")
            .usePlaintext();
    if (imageProcessorUrl.startsWith("discovery://")) {
      builder.nameResolverFactory(
          (NameResolver.Factory)
              new NameResolverProvider() {
                public ServiceDiscoveryNameResolver newNameResolver(
                    URI targetUri, NameResolver.Args args) {
                  log.info("Create discovery for " + targetUri + ", " + args);
                  return new ServiceDiscoveryNameResolver(
                      discoveryClient, targetUri.getHost(), scheduler);
                }

                @Override
                protected boolean isAvailable() {
                  return true;
                }

                @Override
                protected int priority() {
                  return 0;
                }

                @Override
                public String getDefaultScheme() {
                  return "discovery";
                }
              });
    }
    final ManagedChannel managedChannel = builder.build();

    return ProcessImageServiceGrpc.newStub(managedChannel);
  }

  @Bean
  public InitAdminUserIfMissing initAdminUserIfMissing(
      final Properties properties,
      final DataViewService dataViewService,
      final UserManager userManager) {
    return new InitAdminUserIfMissing(properties, dataViewService, userManager);
  }
}
