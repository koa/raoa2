package ch.bergturbenthal.raoa.importer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = {RaoaImporterApplication.class},
    properties = "spring.cloud.kubernetes.enabled=false")
public class RaoaImporterApplicationTests {

  @Test
  public void contextLoads() {}
}
