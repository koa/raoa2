package ch.bergturbenthal.raoa.libs.service;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Function;
import org.eclipse.jgit.lib.ObjectId;
import org.jetbrains.annotations.NotNull;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

public interface FileImporter extends Closeable {
  @NotNull
  Mono<Tuple2<UUID, ObjectId>> importFile(Path file);

  @NotNull
  Mono<Tuple2<UUID, ObjectId>> importFile(final Path file, final String originalFileName);

  @NotNull
  Mono<Boolean> commitAll();

  void close();

  @NotNull
  Mono<Tuple2<UUID, ObjectId>> importFile(
      Path file, String originalFileName, Function<UUID, Mono<Boolean>> authorizer);

  Mono<Tuple2<UUID, ObjectId>> importFileIntoRepository(
      Path file, String originalFileName, UUID selectedRepository);
}
