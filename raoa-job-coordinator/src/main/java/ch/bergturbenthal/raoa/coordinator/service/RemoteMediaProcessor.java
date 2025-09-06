package ch.bergturbenthal.raoa.coordinator.service;

import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

public interface RemoteMediaProcessor {
    Mono<Boolean> processFiles(UUID album, Collection<String> files, String additionalResource);

    void close();
}
