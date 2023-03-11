package ch.bergturbenthal.raoa.processor.media;

import ch.bergturbenthal.raoa.elastic.RaoaElasticConfiguration;
import ch.bergturbenthal.raoa.processor.media.properties.JobProperties;
import ch.bergturbenthal.raoa.processor.media.service.Processor;
import ch.bergturbenthal.raoa.processor.media.service.impl.DefaultProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication()
@Import({RaoaElasticConfiguration.class})
@EnableConfigurationProperties(JobProperties.class)
@ComponentScan(basePackageClasses = {DefaultProcessor.class})
@Slf4j
public class RaoaMediaProcessor {
  public static void main(String[] args) {
    boolean ok = false;
    try (ConfigurableApplicationContext context =
        SpringApplication.run(RaoaMediaProcessor.class, args)) {

      ok = context.getBean(Processor.class).run();
      log.info("Application terminated " + ok);
    } catch (Throwable t) {
      ok = false;
      log.warn("Error processing data ", t);
    }
    System.exit(ok ? 0 : 1);
  }
}
