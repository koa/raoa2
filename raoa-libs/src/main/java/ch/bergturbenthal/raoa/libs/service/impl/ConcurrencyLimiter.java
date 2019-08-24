package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.properties.Properties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Subscription;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoOperator;
import reactor.util.context.Context;

// @Service
@Slf4j
public class ConcurrencyLimiter implements Limiter {
  private static final Object CONTEXT_KEY = new Object();
  private final Properties properties;
  private final MeterRegistry meterRegistry;
  private final AtomicLong requestIdGenerator = new AtomicLong();
  private final Map<Long, LimiterEntry> currentRunningRequests = new ConcurrentHashMap<>();
  private Queue<LimiterEntry> pendingLimiterEntries = new ConcurrentLinkedDeque<>();

  public ConcurrencyLimiter(final Properties properties, final MeterRegistry meterRegistry) {
    this.properties = properties;
    this.meterRegistry = meterRegistry;
    meterRegistry.gauge("limiter.concurrency", this, ConcurrencyLimiter::currentRunningQueries);
    meterRegistry.gauge("limiter.max", properties, Properties::getMaxConcurrent);
    meterRegistry.gauge("limiter.queue", pendingLimiterEntries, Queue::size);
  }

  @Scheduled(fixedDelay = 10 * 1000)
  public void logCurrentPendingrequests() {
    log.info("Current running requests: " + currentRunningRequests);
    log.info("Current pending requests: " + pendingLimiterEntries);
    // processQueue();
  }

  @Override
  public <T> Mono<T> limit(Mono<T> input, String context) {
    try {
      final long currentRequestId = requestIdGenerator.incrementAndGet();

      final Mono<T> inMono = input
          // .log(currentRequestId + " i " + context)
          ;
      final Mono<T> monoOperator =
          new MonoOperator<>(inMono) {
            @Override
            public void subscribe(final CoreSubscriber<? super T> actual) {
              inMono.subscribe(newCoreSubscriber(actual, context, true, currentRequestId));
            }
          };
      return monoOperator // .timeout(Duration.ofSeconds(20))
      // .log(currentRequestId + " a " + context)
      ;
    } catch (Exception ex) {
      log.warn("Cannot process limiter", ex);
      return input;
    }
  }

  @Override
  public <T> Flux<T> limit(Flux<T> input, String context) {
    try {
      final long currentRequestId = requestIdGenerator.incrementAndGet();

      return new FluxOperator<>(input) {
        @Override
        public void subscribe(final CoreSubscriber<? super T> actual) {
          input.subscribe(newCoreSubscriber(actual, context, false, currentRequestId));
        }
      };

    } catch (Exception ex) {
      log.warn("Cannot process limiter", ex);
      return input;
    }
  }

  private void processQueue() {
    // log.info("process: " + describeState());
    for (int i = 0;
        (i < pendingLimiterEntries.size())
            && (currentRunningRequests.size() < properties.getMaxConcurrent());
        i++) {
      final LimiterEntry foundEntry = pendingLimiterEntries.poll();
      if (foundEntry == null) break;
      if (foundEntry.canRun()) currentRunningRequests.put(foundEntry.getRequestId(), foundEntry);
      else pendingLimiterEntries.add(foundEntry);
    }
    final Iterator<LimiterEntry> iterator = currentRunningRequests.values().iterator();
    while (currentRunningQueries() < properties.getMaxConcurrent() && iterator.hasNext()) {
      log.info("Starting: " + describeState());
      iterator.next().getReleaseOneRunnable().run();
    }
    if (currentRunningQueries() >= properties.getMaxConcurrent()) {
      log.info("Blocked " + describeState());
    }
  }

  private int currentRunningQueries() {
    return currentRunningRequests.values().stream()
        .map(LimiterEntry::getStartedRequests)
        .mapToInt(AtomicInteger::get)
        .sum();
  }

  private void countIncomingCase(final String reentrant) {
    meterRegistry.counter("limiter.incoming", "case", reentrant).increment();
  }

  public String describeState() {
    return "("
        + currentRunningQueries()
        + "/"
        + properties.getMaxConcurrent()
        + "), queue: "
        + pendingLimiterEntries.size()
        + "; "
        + currentRunningRequests.size();
  }

  private boolean tryTakeToken() {

    final int currentCount = currentRunningQueries();
    return currentCount < properties.getMaxConcurrent();
  }

  private <T> CoreSubscriber<? super T> newCoreSubscriber(
      final CoreSubscriber<? super T> actual,
      final String context,
      boolean single,
      final long currentRequestId) {
    final String contextId = currentRequestId + " " + context;
    final Context currentContext = actual.currentContext();
    if (currentContext.hasKey(CONTEXT_KEY)) {
      log.info("loop " + currentContext.get(CONTEXT_KEY) + " -> " + contextId);
      return actual;
    } else {
      final Context downContext = currentContext.put(CONTEXT_KEY, contextId);

      AtomicInteger requestedSubscriptions = new AtomicInteger(0);
      Object calcLock = new AtomicReference<String>(contextId);
      AtomicReference<Subscription> currentSubscription = new AtomicReference<>(null);
      AtomicInteger currentRunningCount = new AtomicInteger();
      Runnable tryForwardSubscription =
          () -> {
            final boolean tookSubscription;
            if (tryTakeToken()) {
              // final int remainingSubscriptions;
              synchronized (calcLock) {
                if (requestedSubscriptions.get() > 0) {
                  requestedSubscriptions.decrementAndGet();
                  tookSubscription = true;
                } else {
                  tookSubscription = false;
                  // remainingSubscriptions = requestedSubscriptions.get();
                }
                if (tookSubscription) {
                  currentRunningCount.incrementAndGet();
                }
              }
              if (tookSubscription) {
                currentSubscription.get().request(1);
              }
            }
          };
      AtomicBoolean done = new AtomicBoolean(false);
      Runnable cleanup =
          () -> {
            synchronized (calcLock) {
              done.set(true);
              int removeCount = 0;
              while (currentRunningCount.getAndDecrement() > 0) {

                removeCount += 1;
              }
              final int remainingCount = currentRunningCount.incrementAndGet();
              log.info(contextId + " removed " + removeCount + " entries");
              if (remainingCount == 0) {
                currentRunningRequests.remove(currentRequestId);
                pendingLimiterEntries.removeIf(e -> e.getRequestId() == currentRequestId);
              } else {
                log.warn("Remaining requests on " + contextId + ": " + remainingCount);
              }
            }
            processQueue();
          };

      final LimiterEntry limiterEntry =
          new LimiterEntry(
              context,
              currentRequestId,
              requestedSubscriptions,
              currentRunningCount,
              tryForwardSubscription);

      pendingLimiterEntries.add(limiterEntry);

      return new CoreSubscriber<>() {
        @Override
        @NonNull
        public Context currentContext() {
          return downContext;
        }

        @Override
        public void onSubscribe(final Subscription s) {
          if (!currentSubscription.compareAndSet(null, s)) {
            log.warn("Multi Subscription");
          }
          actual.onSubscribe(
              new Subscription() {
                @Override
                public void request(final long n) {
                  if (done.get()) {
                    log.info("Try to request on a done subscription");
                    return;
                  }
                  synchronized (calcLock) {
                    final int requestCount =
                        (int) Math.min(n, Integer.MAX_VALUE - requestedSubscriptions.get());
                    requestedSubscriptions.addAndGet(requestCount);
                  }
                  processQueue();
                }

                @Override
                public void cancel() {
                  cleanup.run();
                  s.cancel();
                  processQueue();
                }
              });
        }

        @Override
        public void onNext(final T t) {
          // log.info("Next: " + describeState());
          if (done.get()) return;
          actual.onNext(t);
          if (single) cleanup.run();
          else {
            currentRunningCount.decrementAndGet();
          }
          processQueue();
        }

        @Override
        public void onError(final Throwable t) {
          // log.info("Error: " + describeState());
          cleanup.run();
          actual.onError(t);
          processQueue();
        }

        @Override
        public void onComplete() {
          // log.info("Complete: " + describeState());
          cleanup.run();
          actual.onComplete();
          processQueue();
        }
      };
    }
  }

  @Value
  private static class LimiterEntry {
    private String contextDescription;
    private long requestId;
    private AtomicInteger pendingRequests;
    private AtomicInteger startedRequests;
    private Runnable releaseOneRunnable;

    private boolean canRun() {
      return pendingRequests.get() > 0;
    }
  }
}
