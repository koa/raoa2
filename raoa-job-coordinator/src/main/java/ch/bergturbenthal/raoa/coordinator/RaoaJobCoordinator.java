package ch.bergturbenthal.raoa.coordinator;

import ch.bergturbenthal.raoa.coordinator.model.CoordinatorProperties;
import ch.bergturbenthal.raoa.coordinator.resolver.ServiceDiscoveryNameResolver;
import ch.bergturbenthal.raoa.coordinator.service.impl.Poller;
import ch.bergturbenthal.raoa.libs.PatchedElasticsearchConfigurationSupport;
import ch.bergturbenthal.raoa.libs.RaoaLibConfiguration;
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
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {ElasticSearchRestHealthContributorAutoConfiguration.class})
// @EnableConfigurationProperties(ViewerProperties.class)
@Import({RaoaLibConfiguration.class, PatchedElasticsearchConfigurationSupport.class})
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
    final NameResolver.Factory resolverFactory =
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
        };

    final ManagedChannel managedChannel =
        ManagedChannelBuilder.forTarget(coordinatorProperties.getImageProcessorUrl())
            .nameResolverFactory(resolverFactory)
            .defaultLoadBalancingPolicy("round_robin")
            .usePlaintext()
            .build();

    return ProcessImageServiceGrpc.newStub(managedChannel);
  }
}
