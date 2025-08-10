package ch.bergturbenthal.raoa.libs.service;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AsyncService {
    <T> Mono<T> asyncMono(Callable<T> callable);

    default <T> Mono<T> asyncMonoOptional(Callable<Optional<T>> callable) {
        return asyncMono(callable).filter(Optional::isPresent).map(Optional::get);
    }

    <T> Flux<T> asyncFlux(RelaxConsumer<Consumer<T>> sinkHandler);

    @FunctionalInterface
    interface RelaxConsumer<T> {
        void accept(T t) throws Exception;
    }
}
