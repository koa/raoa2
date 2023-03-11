package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.properties.Properties;
import ch.bergturbenthal.raoa.libs.service.AsyncService;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Slf4j
public class ExecutorAsyncService implements AsyncService {
  private final ExecutorService executor;
  private final Scheduler processScheduler =
      Schedulers.newBoundedElastic(
          Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE,
          Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
          "async executor",
          60,
          true);

  public ExecutorAsyncService(final Properties properties) {

    final CustomizableThreadFactory threadFactory = new CustomizableThreadFactory("async");
    threadFactory.setDaemon(true);
    final LinkedBlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>();
    final ThreadPoolExecutor executorService =
        new ThreadPoolExecutor(
            properties.getAsyncThreadCount(),
            properties.getAsyncThreadCount(),
            Duration.ofMinutes(1).toMillis(),
            TimeUnit.MILLISECONDS,
            workQueue,
            threadFactory);
    this.executor = executorService;
  }

  @Override
  public <T> Mono<T> asyncMono(final Callable<T> callable) {
    return Mono.<T>create(
            sink -> {
              AtomicBoolean started = new AtomicBoolean(false);
              AtomicReference<Future<?>> pendingFuture = new AtomicReference<>(null);
              sink.onRequest(
                  requestCount -> {
                    if (requestCount > 0L && started.compareAndSet(false, true)) {
                      pendingFuture.set(
                          executor.submit(
                              () -> {
                                boolean done = false;
                                try {
                                  final T result = callable.call();
                                  if (result == null) sink.success();
                                  else sink.success(result);
                                  done = true;
                                } catch (Throwable e) {
                                  sink.error(new RuntimeException(e));
                                }
                                if (!done) sink.success();
                              }));
                    }
                  });
              sink.onCancel(
                  () -> {
                    final Future<?> future = pendingFuture.getAndSet(null);
                    if (future != null) future.cancel(true);
                    started.set(false);
                  });
            })
        .publishOn(processScheduler);
  }

  @Override
  public <T> Flux<T> asyncFlux(final RelaxConsumer<Consumer<T>> sinkHandler) {
    return Flux.<T>create(
            fluxSink -> {
              Semaphore remainingContingent = new Semaphore(0);
              AtomicBoolean started = new AtomicBoolean(false);
              AtomicReference<Future<?>> runningFuture = new AtomicReference<>(null);
              fluxSink.onRequest(
                  requestCount -> {
                    if (requestCount < 1) return;
                    int maxRelease = Integer.MAX_VALUE - remainingContingent.availablePermits();
                    remainingContingent.release((int) Math.min(requestCount, maxRelease));
                    if (started.compareAndSet(false, true)) {
                      runningFuture.set(
                          executor.submit(
                              () -> {
                                AtomicBoolean done = new AtomicBoolean(false);
                                try {
                                  sinkHandler.accept(
                                      value -> {
                                        if (done.get())
                                          throw new IllegalStateException("Flux is already closed");
                                        try {
                                          // log.info("Take " + value);
                                          remainingContingent.acquire();
                                          // log.info("Semaphore taken");
                                          fluxSink.next(value);
                                          // log.info("Data processed");
                                        } catch (InterruptedException e) {
                                          throw new IllegalStateException(e);
                                        }
                                      });
                                } catch (Exception ex) {
                                  fluxSink.error(ex);
                                } finally {
                                  done.set(true);
                                  fluxSink.complete();
                                }
                              }));
                    }
                  });
              fluxSink.onCancel(
                  () -> {
                    final Future<?> pendingFuture = runningFuture.getAndSet(null);
                    if (pendingFuture != null) pendingFuture.cancel(true);
                  });
            })
        .publishOn(processScheduler);
  }
}
