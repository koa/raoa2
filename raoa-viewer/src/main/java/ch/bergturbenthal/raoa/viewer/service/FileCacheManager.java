package ch.bergturbenthal.raoa.viewer.service;

import java.io.File;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;

public interface FileCacheManager {
  <K> FileCache<K> createCache(
      String id,
      BiFunction<K, File, Mono<File>> objectCreator,
      Function<K, String> filenameGenerator);
}
