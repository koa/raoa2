package ch.bergturbenthal.raoa.coordinator.test;

import ch.bergturbenthal.raoa.coordinator.model.CoordinatorProperties;
import ch.bergturbenthal.raoa.coordinator.service.RemoteMediaProcessor;
import ch.bergturbenthal.raoa.coordinator.service.impl.KubernetesMediaProcessor;
import ch.bergturbenthal.raoa.libs.service.AsyncService;
import ch.bergturbenthal.raoa.libs.service.impl.ExecutorAsyncService;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
public class KubernetesClientTest {
    public static void main(String[] args) throws IOException, InterruptedException {
        final KubernetesClient client = new DefaultKubernetesClient().inNamespace("raoa-dev");
        final CoordinatorProperties properties = new CoordinatorProperties();
        properties.setMediaProcessorTemplate(IOUtils.resourceToString("test-template.yaml", StandardCharsets.UTF_8,
                KubernetesClientTest.class.getClassLoader()));
        final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(2);
        final RemoteMediaProcessor processor = new KubernetesMediaProcessor(client, properties, executorService);

        AsyncService asyncService = new ExecutorAsyncService(Executors.newCachedThreadPool(), Optional.empty());

        asyncService.<String> asyncFlux(consumer -> {
            final BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(new ClassPathResource("testfiles.txt").getInputStream()));
            while (true) {
                final String line = bufferedReader.readLine();
                if (line == null)
                    break;
                consumer.accept(line);
            }
        }).publish(flux -> Flux.merge(flux.filter(f -> f.toLowerCase(Locale.ROOT).endsWith(".jpg")).buffer(1000),
                flux.filter(f1 -> f1.toLowerCase(Locale.ROOT).endsWith(".nef")).buffer(100),
                flux.filter(f2 -> f2.toLowerCase(Locale.ROOT).endsWith(".mp4")).buffer(1)))
                .flatMap(batch -> processor.processFiles(UUID.fromString("b033dda0-a33f-dc23-4ac5-c5d3e5208f26"), batch,
                        Map.of()), 20)
                .log("batch").count().block();
        processor.close();
        executorService.shutdown();
        client.close();
    }
}
