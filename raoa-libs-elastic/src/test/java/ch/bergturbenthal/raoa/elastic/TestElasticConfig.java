package ch.bergturbenthal.raoa.elastic;

import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.springframework.data.elasticsearch.client.ClientConfiguration;

@Slf4j
public class TestElasticConfig {

    @Test
    public void test() {
        String[] hostAndPorts = new String[] { "raoa-es-http:9200" };
        log.info("host and ports: " + Arrays.toString(hostAndPorts));
        ClientConfiguration.MaybeSecureClientConfigurationBuilder builder = ClientConfiguration.builder()
                .connectedTo(hostAndPorts);
        final ClientConfiguration build = builder.build();
        log.info("Client configuration: " + build.getEndpoints());
    }
}
