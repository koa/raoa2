package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.properties.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

@Service
@Slf4j
// @RefreshScope
public class ReactiveLimiter implements Limiter {
  private static final Object CONTEXT_KEY = new Object();
  private final SubLimiter rootLimiter;

  public ReactiveLimiter(final Properties properties) {
    rootLimiter = new SubLimiter(properties.getMaxConcurrent());
  }

  @Override
  public <T> Mono<T> limit(final Mono<T> input, final String context) {
    return limit(input.flux(), context).next();
  }

  @Override
  public <T> Flux<T> limit(final Flux<T> input, final String context) {
    return Flux.create(
        sink -> {
          final QueueEntry<T> queueEntry = new QueueEntry<>(() -> input, sink, context);
          final SubLimiter limiter = sink.currentContext().getOrDefault(CONTEXT_KEY, rootLimiter);
          limiter.queue.add(queueEntry);
          limiter.tryDeqeue(true);
        });
  }

  private static class SubLimiter {
    private final Semaphore semaphore;
    private final Queue<QueueEntry<?>> queue = new ConcurrentLinkedQueue<>();

    public SubLimiter(int count) {
      semaphore = new Semaphore(count, true);
    }

    private void tryDeqeue(final boolean wait) {
      try {
        final int queueSize = queue.size();
        if (semaphore.tryAcquire((!wait || queueSize < 50) ? 0 : 2, TimeUnit.SECONDS)) {
          // log.info("Slot taken " + queueSize);
          final QueueEntry<?> takenEntry = queue.poll();
          if (takenEntry != null) {
            // log.info("entry from queue taken");
            runEntry(takenEntry);
            tryDeqeue(false);
          } else {
            // log.info("No Entry in queue");
            semaphore.release();
          }
        } // else {
        // log.info("No slot available");
        // }
      } catch (final InterruptedException e) {
        log.info("Cannot wait to semaphore", e);
      }
    }

    private void runEntry(final QueueEntry<?> takenEntry) {
      final FluxSink<Object> sink = (FluxSink<Object>) takenEntry.getResultSink();
      SubLimiter subLimiter = new SubLimiter(1);

      final AtomicBoolean done = new AtomicBoolean(false);
      final Disposable subscribe =
          takenEntry
              .getMonoSupplier()
              .get()
              // .timeout(Duration.ofMinutes(1))
              .doFinally(
                  signal -> {
                    // log.info("Signal " + signal);
                    if (!done.getAndSet(true)) {
                      // log.info("release");
                      semaphore.release();
                    }
                    tryDeqeue(false);
                  })
              .subscriberContext(sink.currentContext().put(CONTEXT_KEY, subLimiter))
              .subscribe(sink::next, sink::error, sink::complete);
      sink.onCancel(subscribe);
    }
  }

  @Value
  private static class QueueEntry<R> {
    private Supplier<Flux<R>> monoSupplier;
    private FluxSink<R> resultSink;
    private String context;
  }
}
