package ch.bergturbenthal.raoa.viewer.service;

import java.io.File;
import java.util.function.Function;
import org.eclipse.jgit.lib.ObjectId;
import reactor.core.publisher.Mono;

public interface FileCacheManager {
  FileCache createCache(String id, Function<ObjectId, Mono<File>> objectCreator);
}
