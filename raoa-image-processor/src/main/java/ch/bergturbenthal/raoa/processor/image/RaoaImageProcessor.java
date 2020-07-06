package ch.bergturbenthal.raoa.processor.image;

import ch.bergturbenthal.raoa.elastic.RaoaElasticConfiguration;
import ch.bergturbenthal.raoa.processor.image.interfaces.GrpcController;
import ch.bergturbenthal.raoa.processor.image.service.impl.DefaultImageProcessor;
import com.netflix.concurrency.limits.Limiter;
import com.netflix.concurrency.limits.MetricRegistry;
import com.netflix.concurrency.limits.grpc.server.ConcurrencyLimitServerInterceptor;
import com.netflix.concurrency.limits.grpc.server.GrpcServerRequestContext;
import com.netflix.concurrency.limits.limit.FixedLimit;
import com.netflix.concurrency.limits.limiter.AbstractLimiter;
import com.netflix.concurrency.limits.limiter.SimpleLimiter;
import io.grpc.ServerInterceptor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.function.Supplier;
import org.lognet.springboot.grpc.GRpcGlobalInterceptor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.elasticsearch.ElasticSearchRestHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {ElasticSearchRestHealthContributorAutoConfiguration.class})
// @EnableConfigurationProperties(ViewerProperties.class)
@Import({RaoaElasticConfiguration.class})
@ComponentScan(basePackageClasses = {DefaultImageProcessor.class, GrpcController.class})
@EnableScheduling
public class RaoaImageProcessor {
  public static void main(String[] args) {
    SpringApplication.run(RaoaImageProcessor.class, args);
  }

  @Bean
  @GRpcGlobalInterceptor
  @Order(2)
  public ServerInterceptor grpcThrottling(
      final MetricRegistry metricRegistry, final MeterRegistry meterRegistry) {
    final SimpleLimiter<GrpcServerRequestContext> limiter =
        SimpleLimiter.newBuilder().metricRegistry(metricRegistry).limit(FixedLimit.of(10)).build();
    meterRegistry.gauge("grpc.concurrency.inflight", limiter, AbstractLimiter::getInflight);
    final Counter reqCount =
        meterRegistry.counter("grpc.concurrency.acquired", "throttled", "false");
    final Counter reqThrottled =
        meterRegistry.counter("grpc.concurrency.acquired", "throttled", "true");
    meterRegistry.gauge("grpc.concurrency.limit", limiter, AbstractLimiter::getLimit);
    final Limiter<GrpcServerRequestContext> metricsWrapper =
        context -> {
          final Optional<Limiter.Listener> acquire = limiter.acquire(context);
          if (acquire.isPresent()) {
            reqCount.increment();
          } else {
            reqThrottled.increment();
          }
          return acquire;
        };
    return ConcurrencyLimitServerInterceptor.newBuilder(metricsWrapper).build();
  }

  @Bean
  public MetricRegistry metricRegistry(final MeterRegistry meterRegistry) {
    return new MetricRegistry() {
      private final String PREFIX = "grpc.netflix.concurrency.";

      @Override
      public SampleListener registerDistribution(
          final String id, final String... tagNameValuePairs) {
        final DistributionSummary summary = meterRegistry.summary(PREFIX + id, tagNameValuePairs);
        return value -> summary.record(value.doubleValue());
      }

      @Override
      public void registerGauge(
          final String id, final Supplier<Number> supplier, final String... tagNameValuePairs) {
        Gauge.builder(PREFIX + id, supplier, i -> i.get().doubleValue())
            .tags(tagNameValuePairs)
            .register(meterRegistry);
      }
    };
  }
}
