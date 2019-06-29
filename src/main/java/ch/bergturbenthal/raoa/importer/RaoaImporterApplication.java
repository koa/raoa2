package ch.bergturbenthal.raoa.importer;

import ch.bergturbenthal.raoa.importer.domain.model.Properties;
import ch.bergturbenthal.raoa.importer.domain.service.Importer;
import java.io.IOException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;

@ComponentScan(basePackageClasses = {Properties.class, Importer.class})
@SpringBootApplication
public class RaoaImporterApplication {
  public static void main(String[] args) throws IOException {
    try (ConfigurableApplicationContext applicationContext =
        SpringApplication.run(RaoaImporterApplication.class, args)) {
      final Importer importer = applicationContext.getBean(Importer.class);
      final Properties properties = applicationContext.getBean(Properties.class);
      importer.importDirectories(properties.getMedia(), properties.getRepository());
    }
  }
}
