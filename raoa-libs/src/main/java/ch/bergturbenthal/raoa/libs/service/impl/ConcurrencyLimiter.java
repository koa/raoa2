package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.properties.Properties;
import java.util.Queue;
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
  private AtomicInteger currentRunningCount = new AtomicInteger(0);
  private Queue<Supplier<Boolean>> pendingSubscriptions = new ConcurrentLinkedDeque<>();
  private AtomicInteger counter = new AtomicInteger(0);

  public ConcurrencyLimiter(final Properties properties) {
    this.properties = properties;
  }

  public <T> Mono<T> limit(Mono<T> input) {

    int currentIndex = counter.incrementAndGet();
    AtomicBoolean ownsSlot = new AtomicBoolean(false);
    return input
        .subscriberContext(c -> c.put(CONTEXT_KEY, ""))
        .delaySubscription(
            Mono.create(
                sink -> {
                  if (sink.currentContext().hasKey(CONTEXT_KEY)) {
                    // log.info("Looped");
                    sink.success(Boolean.TRUE);
                  } else if (tryTakeToken()) {
                    ownsSlot.set(true);
                    sink.success(Boolean.TRUE);
                  } else {
                    // log.info(currentIndex + " Put to queue " + describeState());
                    pendingSubscriptions.add(
                        () -> {
                          if (tryTakeToken()) {
                            // log.info(currentIndex + " Started from queue " + describeState());
                            ownsSlot.set(true);
                            sink.success(Boolean.TRUE);
                            return true;
                          }
                          return false;
                        });
                  }
                }))
        .doFinally(
            signal -> {
              if (ownsSlot.get()) currentRunningCount.decrementAndGet();
              // log.info(                  currentIndex + " Released: " + signal + ", " + started +
              // ", " + describeState());
              while (currentRunningCount.get() < properties.getMaxConcurrent()) {
                final Supplier<Boolean> taken = pendingSubscriptions.poll();
                if (taken == null) break;
                if (!taken.get()) {
                  pendingSubscriptions.add(taken);
                  break;
                }
              }
            })
    // .log("limit-" + currentIndex)
    ;
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
    }
  }
}
