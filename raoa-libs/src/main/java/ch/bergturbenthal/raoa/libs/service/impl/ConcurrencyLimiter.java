package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.properties.Properties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Subscription;
import org.springframework.stereotype.Service;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxOperator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoOperator;
import reactor.util.context.Context;

@Service
@Slf4j
public class ConcurrencyLimiter {
  private static final Object CONTEXT_KEY = new Object();
  private final Properties properties;
  private final MeterRegistry meterRegistry;
  private AtomicInteger currentRunningCount = new AtomicInteger(0);
  private Map<Object, Runnable> currentWaitingEntries = new ConcurrentHashMap<>();

  public ConcurrencyLimiter(final Properties properties, final MeterRegistry meterRegistry) {
    this.properties = properties;
    this.meterRegistry = meterRegistry;
    meterRegistry.gauge("limiter.concurrency", currentRunningCount);
    meterRegistry.gauge("limiter.max", properties, Properties::getMaxConcurrent);
    meterRegistry.gauge("limiter.queue", currentWaitingEntries, Map::size);
  }

  public <T> Mono<T> limit(Mono<T> input, String context) {
    try {

      return new MonoOperator<>(input) {
        @Override
        public void subscribe(final CoreSubscriber<? super T> actual) {
          input.subscribe(newCoreSubscriber(actual, context, true));
        }
      };
    } catch (Exception ex) {
      log.warn("Cannot process limiter", ex);
      return input;
    }
  }

  public <T> Flux<T> limit(Flux<T> input, String context) {
    try {
      return new FluxOperator<>(input) {
        @Override
        public void subscribe(final CoreSubscriber<? super T> actual) {
          input.subscribe(newCoreSubscriber(actual, context, false));
        }
      };

    } catch (Exception ex) {
      log.warn("Cannot process limiter", ex);
      return input;
    }
  }

  private void putTokenBack() {
    currentRunningCount.decrementAndGet();
  }

  private void processQueue() {
    log.info("process: " + describeState());
    final Iterator<Runnable> iterator = currentWaitingEntries.values().iterator();
    while (currentRunningCount.get() < properties.getMaxConcurrent() && iterator.hasNext()) {
      final Runnable runnable = iterator.next();
      runnable.run();
      runnable.run();
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
        + currentWaitingEntries.size();
  }

  private boolean tryTakeToken() {
    while (true) {
      final int currentCount = currentRunningCount.get();
      if (currentCount >= properties.getMaxConcurrent()) return false;
      if (currentRunningCount.compareAndSet(currentCount, currentCount + 1)) return true;
      log.info("repeat take");
    }
  }

  private <T> CoreSubscriber<? super T> newCoreSubscriber(
      final CoreSubscriber<? super T> actual, final String context, boolean single) {
    AtomicInteger subscriptionCount = new AtomicInteger(0);
    final Context currentContext = actual.currentContext();
    if (currentContext.hasKey(CONTEXT_KEY)) {
      log.info("loop " + currentContext.get(CONTEXT_KEY) + " -> " + context);
      return actual;
    } else {
      final Context downContext = currentContext.put(CONTEXT_KEY, context);
      AtomicInteger requestedSubscriptions = new AtomicInteger(0);
      Object calcLock = new AtomicReference<String>(context);
      AtomicReference<Subscription> currentSubscription = new AtomicReference<>(null);
      AtomicInteger currentRunningCount = new AtomicInteger();
      Runnable tryForwardSubscription =
          () -> {
            final boolean tookSubscription;
            if (tryTakeToken()) {
              synchronized (calcLock) {
                if (requestedSubscriptions.get() > 0) {
                  requestedSubscriptions.decrementAndGet();
                  tookSubscription = true;
                } else tookSubscription = false;
              }
              if (tookSubscription) {
                currentRunningCount.incrementAndGet();
                currentSubscription.get().request(1);
              } else putTokenBack();
            }
          };
      Runnable cleanup =
          () -> {
            currentWaitingEntries.remove(calcLock);
            while (currentRunningCount.getAndDecrement() > 0) putTokenBack();
            currentRunningCount.incrementAndGet();
            processQueue();
          };

      currentWaitingEntries.put(calcLock, tryForwardSubscription);

      return new CoreSubscriber<>() {
        @Override
        public Context currentContext() {
          return downContext;
        }

        @Override
        public void onSubscribe(final Subscription s) {
          if (!currentSubscription.compareAndSet(null, s)) {
            log.warn("Multi Subscription: " + subscriptionCount);
          }
          actual.onSubscribe(
              new Subscription() {
                @Override
                public void request(final long n) {
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
          log.info("Next: " + describeState());
          if (single) cleanup.run();
          else {
            currentRunningCount.decrementAndGet();
            putTokenBack();
          }

          actual.onNext(t);
          processQueue();
        }

        @Override
        public void onError(final Throwable t) {
          log.info("Error: " + describeState());
          cleanup.run();
          actual.onError(t);
          processQueue();
        }

        @Override
        public void onComplete() {
          log.info("Complete: " + describeState());
          cleanup.run();
          actual.onComplete();
          processQueue();
        }
      };
    }
  }
}
