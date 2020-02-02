package ch.bergturbenthal.raoa.processor.image;

import ch.bergturbenthal.raoa.libs.PatchedElasticsearchConfigurationSupport;
import ch.bergturbenthal.raoa.libs.RaoaLibConfiguration;
import ch.bergturbenthal.raoa.processor.image.interfaces.GrpcController;
import ch.bergturbenthal.raoa.processor.image.service.impl.DefaultImageProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.elasticsearch.ElasticSearchRestHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {ElasticSearchRestHealthContributorAutoConfiguration.class})
// @EnableConfigurationProperties(ViewerProperties.class)
@Import({RaoaLibConfiguration.class, PatchedElasticsearchConfigurationSupport.class})
@ComponentScan(basePackageClasses = {DefaultImageProcessor.class, GrpcController.class})
@EnableScheduling
public class RaoaImageProcessor {
  public static void main(String[] args) {
    SpringApplication.run(RaoaImageProcessor.class, args);
  }
}
