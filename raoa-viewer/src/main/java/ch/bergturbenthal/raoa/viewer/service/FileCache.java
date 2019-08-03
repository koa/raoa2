package ch.bergturbenthal.raoa.viewer.service;

import java.io.File;
import java.util.function.Predicate;
import reactor.core.publisher.Mono;

public interface FileCache<K> {
  default Mono<File> take(K objectId) {
    return take(objectId, v -> true);
  }

  Mono<File> take(K objectId, Predicate<File> validate);
}
