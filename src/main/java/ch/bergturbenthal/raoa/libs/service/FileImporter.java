package ch.bergturbenthal.raoa.libs.service;

import java.io.Closeable;
import java.nio.file.Path;
import reactor.core.publisher.Mono;

public interface FileImporter extends Closeable {
  Mono<Boolean> importFile(Path file);

  Mono<Boolean> commitAll();

  void close();
}
