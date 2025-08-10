package ch.bergturbenthal.raoa.coordinator.service;

import java.util.Collection;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface RemoteMediaProcessor {
    Mono<Boolean> processFiles(UUID album, Collection<String> files);

    void close();
}
