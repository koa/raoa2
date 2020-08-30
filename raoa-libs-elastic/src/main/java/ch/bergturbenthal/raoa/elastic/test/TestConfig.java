package ch.bergturbenthal.raoa.elastic.test;

import ch.bergturbenthal.raoa.libs.RaoaLibConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.util.Collections;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.reactive.DefaultReactiveElasticsearchClient;
import org.springframework.data.elasticsearch.client.reactive.ReactiveElasticsearchClient;
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchTemplate;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

@Configuration
@Import({RaoaLibConfiguration.class})
public class TestConfig {
  @Bean
  public ElasticsearchContainer initElasticsearch() {
    // Create the elasticsearch container.
    ElasticsearchContainer container =
        new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:7.4.0");

    // Start the container. This step might take some time...
    container.start();
    return container;
  }

  @Bean("reactiveElasticsearchTemplate")
  public ReactiveElasticsearchTemplate reactiveElasticsearchTemplate(
      ElasticsearchContainer container) {
    final InetSocketAddress endpoints =
        InetSocketAddress.createUnresolved(
            container.getContainerIpAddress(), container.getMappedPort(9200));
    final ClientConfiguration clientConfiguration = ClientConfiguration.create(endpoints);

    final ReactiveElasticsearchClient client =
        DefaultReactiveElasticsearchClient.create(clientConfiguration);
    return new ReactiveElasticsearchTemplate(client);
  }

  @Bean
  public MeterRegistry meterRegistry() {
    return new SimpleMeterRegistry();
  }

  @Bean
  @ConditionalOnMissingBean
  @Primary
  @Order(100)
  public ElasticsearchRestClientProperties restClientProperties(ElasticsearchContainer container) {

    final ElasticsearchRestClientProperties properties = new ElasticsearchRestClientProperties();

    properties.setUris(Collections.singletonList("http://" + container.getHttpHostAddress()));
    return properties;
  }
}
