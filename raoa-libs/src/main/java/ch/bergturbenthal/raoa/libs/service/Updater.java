package ch.bergturbenthal.raoa.libs.service;

import java.io.Closeable;
import java.nio.file.Path;
import org.eclipse.jgit.lib.ObjectId;
import reactor.core.publisher.Mono;

public interface Updater extends Closeable {
  Mono<ObjectId> importFile(Path file, String name);

  Mono<ObjectId> importFile(Path file, String name, boolean replaceIfExists);

  Mono<Boolean> removeFile(String name);

  Mono<Boolean> commit();

  Mono<Boolean> commit(String message);

  void close();
}
