package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.properties.Properties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
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
  private final MeterRegistry meterRegistry;

  public ReactiveLimiter(final Properties properties, final MeterRegistry meterRegistry) {
    rootLimiter = new SubLimiter(properties.getMaxConcurrent());
    meterRegistry.gauge("reactive-limiter.queue-length", rootLimiter.queue, Deque::size);
    meterRegistry.gauge("reactive-limiter.running", rootLimiter, SubLimiter::currentRunningEntries);
    meterRegistry.gauge("reactive-limiter.limit", properties, Properties::getMaxConcurrent);
    this.meterRegistry = meterRegistry;
  }

  @Override
  public <T> Mono<T> limit(final Mono<T> input, final String context) {
    meterRegistry.counter("reactive-limiter.incoming").increment();
    AtomicReference<Instant> start = new AtomicReference<>(Instant.now());
    return doLimit(input.flux(), context)
        .doOnSubscribe(sub -> start.set(Instant.now()))
        .singleOrEmpty()
        .doFinally(
            signal ->
                meterRegistry
                    .timer("reactive-limiter.outgoing", "signal", signal.name())
                    .record(Duration.between(start.get(), Instant.now())));
  }

  @Override
  public <T> Flux<T> limit(final Flux<T> input, final String context) {
    meterRegistry.counter("reactive-limiter.incoming").increment();
    AtomicReference<Instant> start = new AtomicReference<>(Instant.now());
    return doLimit(input, context)
        .doOnSubscribe(sub -> start.set(Instant.now()))
        .doFinally(
            signal ->
                meterRegistry
                    .timer("reactive-limiter.outgoing", "signal", signal.name())
                    .record(Duration.between(start.get(), Instant.now())));
  }

  private <T> Flux<T> doLimit(final Flux<T> input, final String context) {
    return Flux.<T>create(
        sink -> {
          final QueueEntry<T> queueEntry = new QueueEntry<>(() -> input, sink, context);
          final SubLimiter limiter = sink.currentContext().getOrDefault(CONTEXT_KEY, rootLimiter);
          limiter.enqueue(queueEntry);
        });
  }

  private static class SubLimiter {
    private final int maxRunning;
    private final Deque<QueueEntry<?>> queue = new ConcurrentLinkedDeque<>();
    private final List<QueueEntry<?>> runningEntries = new ArrayList<>();
    private final Object lock = new Object();

    public SubLimiter(int count) {
      maxRunning = count;
    }

    public int currentRunningEntries() {
      synchronized (lock) {
        return runningEntries.size();
      }
    }

    public void enqueue(QueueEntry<?> entry) {
      queue.add(entry);
      tryDeqeue(true);
    }

    private void tryDeqeue(final boolean wait) {
      try {
        final QueueEntry<?> takenEntry;
        synchronized (lock) {
          if (runningEntries.size() < maxRunning) {
            takenEntry = queue.pollFirst();
            if (takenEntry != null) runningEntries.add(takenEntry);
          } else takenEntry = null;
        }
        if (takenEntry != null) {
          runEntry(takenEntry);
          tryDeqeue(false);
        }

        // else {
        // log.info("No slot available");
        // }
      } catch (final Exception e) {
        log.info("Cannot Dequeue", e);
      }
    }

    private void runEntry(final QueueEntry<?> takenEntry) {
      final FluxSink<Object> sink = (FluxSink<Object>) takenEntry.getResultSink();
      SubLimiter subLimiter = new SubLimiter(1);
      final Disposable subscribe =
          takenEntry
              .getMonoSupplier()
              .get()
              .timeout(Duration.ofMinutes(5))
              .doFinally(
                  signal -> {
                    // log.info("Signal " + signal);
                    synchronized (lock) {
                      final boolean remove = runningEntries.remove(takenEntry);
                      if (!remove) log.info("Missing entry: " + takenEntry.context);
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
