package ch.bergturbenthal.raoa.coordinator.service;

import io.fabric8.kubernetes.api.model.Quantity;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface RemoteMediaProcessor {
    Mono<Boolean> processFiles(UUID album, Collection<String> files, Map<String, Quantity> addidionalResources);

    void close();
}
