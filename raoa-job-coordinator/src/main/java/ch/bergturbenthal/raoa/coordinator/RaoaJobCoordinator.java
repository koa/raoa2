package ch.bergturbenthal.raoa.coordinator;

import ch.bergturbenthal.raoa.coordinator.service.impl.Poller;
import ch.bergturbenthal.raoa.libs.PatchedElasticsearchConfigurationSupport;
import ch.bergturbenthal.raoa.libs.RaoaLibConfiguration;
import ch.bergturbenthal.raoa.libs.model.kafka.ProcessImageRequest;
import ch.bergturbenthal.raoa.libs.serializer.ObjectIdSerializer;
import ch.bergturbenthal.raoa.libs.serializer.ProcessImageRequestSerializer;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.elasticsearch.ElasticSearchRestHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {ElasticSearchRestHealthContributorAutoConfiguration.class})
// @EnableConfigurationProperties(ViewerProperties.class)
@Import({RaoaLibConfiguration.class, PatchedElasticsearchConfigurationSupport.class})
@ComponentScan(basePackageClasses = {Poller.class})
@EnableScheduling
public class RaoaJobCoordinator {
  public static void main(String[] args) {
    SpringApplication.run(RaoaJobCoordinator.class, args);
  }

  @Bean
  public NewTopic processImageTopic() {
    return TopicBuilder.name("process-image").partitions(20).build();
  }

  @Bean
  public ProducerFactory<ObjectId, ProcessImageRequest> pf(KafkaProperties properties) {
    Map<String, Object> props = properties.buildProducerProperties();
    return new DefaultKafkaProducerFactory<>(
        props, new ObjectIdSerializer(), new ProcessImageRequestSerializer());
  }

  @Bean
  public KafkaTemplate<?, ?> kafkaTemplate(
      ProducerFactory<?, ?> kafkaProducerFactory,
      ProducerListener<Object, Object> kafkaProducerListener,
      ObjectProvider<RecordMessageConverter> messageConverter,
      KafkaProperties properties) {
    KafkaTemplate<Object, Object> kafkaTemplate =
        new KafkaTemplate<Object, Object>((ProducerFactory<Object, Object>) kafkaProducerFactory);
    messageConverter.ifUnique(kafkaTemplate::setMessageConverter);
    kafkaTemplate.setProducerListener(kafkaProducerListener);
    kafkaTemplate.setDefaultTopic(properties.getTemplate().getDefaultTopic());
    return kafkaTemplate;
  }
}
