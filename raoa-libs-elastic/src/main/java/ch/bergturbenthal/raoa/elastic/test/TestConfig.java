package ch.bergturbenthal.raoa.elastic.test;

import ch.bergturbenthal.raoa.libs.RaoaLibConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collections;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
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

  @Bean
  public MeterRegistry meterRegistry() {
    return new SimpleMeterRegistry();
  }

  @Bean
  @ConditionalOnMissingBean
  @Primary
  @Order(100)
  public ElasticsearchProperties restClientProperties(ElasticsearchContainer container) {

    final ElasticsearchProperties properties = new ElasticsearchProperties();

    properties.setUris(Collections.singletonList("http://" + container.getHttpHostAddress()));
    return properties;
  }
}
