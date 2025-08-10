package ch.bergturbenthal.raoa.libs.service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface Limiter {
    <T> Mono<T> limit(Mono<T> input, String context);

    <T> Flux<T> limit(Flux<T> input, String context);
}
