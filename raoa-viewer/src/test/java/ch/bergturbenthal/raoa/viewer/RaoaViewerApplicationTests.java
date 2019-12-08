package ch.bergturbenthal.raoa.viewer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "security.oauth2.resource.jwk.key-set-uri=http://dummy.local/",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://dummy.local/"
    })
public class RaoaViewerApplicationTests {

  @Test
  public void contextLoads() {}
}
