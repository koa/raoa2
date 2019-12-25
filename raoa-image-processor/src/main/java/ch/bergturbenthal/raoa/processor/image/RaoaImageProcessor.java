package ch.bergturbenthal.raoa.processor.image;

import ch.bergturbenthal.raoa.libs.PatchedElasticsearchConfigurationSupport;
import ch.bergturbenthal.raoa.libs.RaoaLibConfiguration;
import ch.bergturbenthal.raoa.libs.model.kafka.ProcessImageRequest;
import ch.bergturbenthal.raoa.libs.serializer.ObjectIdDeserializer;
import ch.bergturbenthal.raoa.libs.serializer.ProcessImageRequestDeserializer;
import ch.bergturbenthal.raoa.processor.image.interfaces.ImageProcessor;
import java.util.Map;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.elasticsearch.ElasticSearchRestHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {ElasticSearchRestHealthContributorAutoConfiguration.class})
// @EnableConfigurationProperties(ViewerProperties.class)
@Import({RaoaLibConfiguration.class, PatchedElasticsearchConfigurationSupport.class})
@ComponentScan(basePackageClasses = {ImageProcessor.class})
@EnableScheduling
@EnableKafka
public class RaoaImageProcessor {
  public static void main(String[] args) {
    SpringApplication.run(RaoaImageProcessor.class, args);
  }

  @Bean
  KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<ObjectId, ProcessImageRequest>>
      kafkaListenerContainerFactory(
          final ConsumerFactory<ObjectId, ProcessImageRequest> consumerFactory) {
    ConcurrentKafkaListenerContainerFactory<ObjectId, ProcessImageRequest> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.setConcurrency(3);
    factory.getContainerProperties().setPollTimeout(3000);
    return factory;
  }

  @Bean
  public DefaultKafkaConsumerFactory<ObjectId, ProcessImageRequest> consumerFactory(
      KafkaProperties properties) {
    Map<String, Object> props = properties.buildProducerProperties();
    return new DefaultKafkaConsumerFactory<>(
        props, new ObjectIdDeserializer(), new ProcessImageRequestDeserializer());
  }
}
