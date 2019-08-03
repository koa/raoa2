package ch.bergturbenthal.raoa.libs.service;

import java.io.Closeable;
import java.nio.file.Path;
import reactor.core.publisher.Mono;

public interface Updater extends Closeable {
  Mono<Boolean> importFile(Path file, String name);

  Mono<Boolean> importFile(Path file, String name, boolean replaceIfExists);

  Mono<Boolean> commit();

  Mono<Boolean> commit(String message);

  void close();
}
