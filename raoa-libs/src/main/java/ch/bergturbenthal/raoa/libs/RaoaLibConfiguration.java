package ch.bergturbenthal.raoa.libs;

import ch.bergturbenthal.raoa.libs.properties.Properties;
import ch.bergturbenthal.raoa.libs.service.AsyncService;
import ch.bergturbenthal.raoa.libs.service.impl.BareGitAccess;
import ch.bergturbenthal.raoa.libs.service.impl.ExecutorAsyncService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(Properties.class)
@ComponentScan(basePackageClasses = BareGitAccess.class)
@Slf4j
public class RaoaLibConfiguration {

    @Bean
    public AsyncService asyncService(final Properties properties, MeterRegistry meterRegistry) {

        final CustomizableThreadFactory threadFactory = new CustomizableThreadFactory("async");
        threadFactory.setDaemon(true);
        final LinkedBlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>();
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(properties.getAsyncThreadCount(),
                properties.getAsyncThreadCount(), Duration.ofMinutes(1).toMillis(), TimeUnit.MILLISECONDS, workQueue,
                threadFactory);

        return new ExecutorAsyncService(executor, Optional.of(meterRegistry));
    }

    @Bean
    ScheduledExecutorService executorService() {
        return Executors.newScheduledThreadPool(10, new CustomizableThreadFactory("raoa-lib-scheduled"));
    }
}
