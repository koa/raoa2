package ch.bergturbenthal.raoa.viewer.service;

import java.io.File;
import reactor.core.publisher.Mono;

public interface FileCache<K> {
  Mono<File> take(K objectId);
}
