package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.properties.Properties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class ConcurrencyLimiter {
  private static final Object CONTEXT_KEY = new Object();
  private final Properties properties;
  private final MeterRegistry meterRegistry;
  private final Map<Object, Instant> currentRunningEntries = new ConcurrentHashMap<>();
  private AtomicInteger currentRunningCount = new AtomicInteger(0);
  private Queue<Supplier<Boolean>> pendingSubscriptions = new ConcurrentLinkedDeque<>();
  private AtomicInteger counter = new AtomicInteger(0);

  public ConcurrencyLimiter(final Properties properties, final MeterRegistry meterRegistry) {
    this.properties = properties;
    this.meterRegistry = meterRegistry;
    meterRegistry.gauge("limiter.concurrency", currentRunningCount);
    meterRegistry.gauge("limiter.max", properties, Properties::getMaxConcurrent);
    meterRegistry.gauge("limiter.queue", pendingSubscriptions, Collection::size);
  }

  public <T> Mono<T> limit(Mono<T> input) {
    try {
      return input
          .subscriberContext(c -> c.put(CONTEXT_KEY, ""))
          .delaySubscription(
              Mono.create(
                  sink -> {
                    AtomicBoolean ownsSlot = new AtomicBoolean(false);
                    sink.onDispose(
                        () -> {
                          meterRegistry.counter("limiter.requested").increment();
                          if (ownsSlot.compareAndSet(true, false)) {
                            currentRunningCount.decrementAndGet();
                          }
                        });
                    sink.onCancel(() -> log.info("Cancelling"));
                    if (sink.currentContext().hasKey(CONTEXT_KEY)) {
                      countIncomingCase("reentrant");
                      // log.info("Looped");
                      sink.success(Boolean.TRUE);
                      return;
                    }
                    countIncomingCase("queue");
                    // log.info(currentIndex + " Put to queue " + describeState());
                    Instant waitStart = Instant.now();
                    pendingSubscriptions.add(
                        () -> {
                          if (tryTakeToken()) {
                            // log.info(currentIndex + " Started from queue " +
                            // describeState());
                            if (ownsSlot.compareAndSet(false, true)) {
                              meterRegistry
                                  .timer("limiter.wait")
                                  .record(Duration.between(waitStart, Instant.now()));
                              sink.success(Boolean.TRUE);
                              return true;
                            } else {
                              log.info("failed take queue token");
                              currentRunningCount.decrementAndGet();
                            }
                          }
                          return false;
                        });
                    processQueue();
                  }))
          .doFinally(
              signal -> {
                processQueue();
              });
      // .log("limit-" + currentIndex)
    } catch (Exception ex) {
      log.warn("Cannot process limiter", ex);
      return input;
    }
  }

  private void processQueue() {
    while (currentRunningCount.get() < properties.getMaxConcurrent()) {
      final Supplier<Boolean> taken = pendingSubscriptions.poll();
      if (taken == null) break;
      if (!taken.get()) {
        pendingSubscriptions.add(taken);
        break;
      }
    }
  }

  private void countIncomingCase(final String reentrant) {
    meterRegistry.counter("limiter.incoming", "case", reentrant).increment();
  }

  public String describeState() {
    return "("
        + currentRunningCount.get()
        + "/"
        + properties.getMaxConcurrent()
        + "), queue: "
        + pendingSubscriptions.size();
  }

  private boolean tryTakeToken() {
    while (true) {
      final int currentCount = currentRunningCount.get();
      if (currentCount >= properties.getMaxConcurrent()) return false;
      if (currentRunningCount.compareAndSet(currentCount, currentCount + 1)) return true;
      log.info("repeat take");
    }
  }
}
