package ch.bergturbenthal.raoa.importer;

import ch.bergturbenthal.raoa.libs.test.TestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = {TestConfig.class, RaoaImporterApplication.class},
    properties = "spring.cloud.kubernetes.enabled=false")
public class RaoaImporterApplicationTests {

  @Test
  public void contextLoads() {}
}
