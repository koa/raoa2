package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.service.AsyncService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
public class ExecutorAsyncService implements AsyncService {
    private final ExecutorService executorService;
    private final Optional<MeterRegistry> meterRegistryOptional;

    public ExecutorAsyncService(final ExecutorService executorService, Optional<MeterRegistry> meterRegistryOptional) {
        this.executorService = executorService;
        this.meterRegistryOptional = meterRegistryOptional;
    }

    @Override
    public <T> Mono<T> asyncMonoOptional(Callable<Optional<T>> callable) {
        return asyncMono(callable).filter(Optional::isPresent).map(Optional::get);
    }

    @Override
    public <T> Mono<T> asyncMono(Callable<T> callable) {
        return Mono.<T> create(monoSink -> {
            final AtomicReference<Future<?>> runningFuture = new AtomicReference<>(null);
            final AtomicBoolean interrupted = new AtomicBoolean(false);
            monoSink.onRequest(count -> {
                if (count > 0) {
                    runningFuture.updateAndGet(existingFuture -> existingFuture != null ? existingFuture
                            : executorService.submit(meterRunnable("asyncMono", () -> {
                                boolean done = false;
                                try {
                                    monoSink.success(callable.call());
                                    done = true;
                                } catch (Exception e) {
                                    if (!interrupted.get())
                                        monoSink.error(e);
                                } finally {
                                    if (!done)
                                        monoSink.success();
                                }
                            })));
                }
            });
            monoSink.onCancel(() -> {
                final Future<?> pendingFuture = runningFuture.getAndSet(null);
                interrupted.set(true);
                if (pendingFuture != null)
                    pendingFuture.cancel(true);
            });
        });
    }

    private Runnable meterRunnable(final String methodName, final Runnable runnable) {
        return meterRegistryOptional.map(meterRegistry -> (Runnable) () -> meterRegistry
                .timer("async.task.run", "method", methodName).record(runnable)).orElse(runnable);
    }

    @Override
    public <T> Flux<T> asyncFlux(RelaxConsumer<Consumer<T>> sinkHandler) {
        return Flux.<T> create(fluxSink -> {
            Semaphore freeSlots = new Semaphore(0);
            fluxSink.onRequest(count -> freeSlots.release((int) Math.min(count, Integer.MAX_VALUE)));
            AtomicBoolean interrupted = new AtomicBoolean(false);
            final Future<?> future = executorService.submit(meterRunnable("asyncFlux", () -> {
                final Consumer<T> dataConsumer = nextElement -> {
                    if (interrupted.get()) {
                        throw new RuntimeException("Flux is already cancelled", new InterruptedException());
                    }
                    try {
                        freeSlots.acquire(1);
                        try {
                            fluxSink.next(nextElement);
                        } catch (Exception e) {
                            log.error("Error while processing next element {}", nextElement, e);
                            if (!interrupted.get())
                                fluxSink.error(e);
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                };
                try {
                    sinkHandler.accept(dataConsumer);
                } catch (Exception ex) {
                    if (!interrupted.get())
                        fluxSink.error(ex);
                    else
                        log.debug("Exception on interrupted process", ex);
                } finally {
                    if (!interrupted.get())
                        fluxSink.complete();
                }
            }));
            fluxSink.onCancel(() -> {
                interrupted.set(true);
                future.cancel(true);
            });
        });
    }
}
