package ch.bergturbenthal.raoa.viewer.service.impl;

import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.FileCache;
import ch.bergturbenthal.raoa.viewer.service.FileCacheManager;
import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class DefaultFileCacheManager implements FileCacheManager {
  private final ViewerProperties properties;
  private final Map<String, FileCache> existingCaches =
      Collections.synchronizedMap(new HashMap<>());

  public DefaultFileCacheManager(final ViewerProperties properties) {
    this.properties = properties;
  }

  @Override
  public FileCache createCache(
      final String cacheId, final Function<ObjectId, Mono<File>> objectCreator) {

    return existingCaches.computeIfAbsent(
        cacheId,
        name -> {
          final File cacheDir = new File(properties.getCacheDir(), name);
          final Map<ObjectId, Mono<File>> pendingCreations =
              Collections.synchronizedMap(new HashMap<>());

          return objectId -> {
            final File alreadyCachedFile = new File(cacheDir, objectId.name());
            if (alreadyCachedFile.exists()) return Mono.just(alreadyCachedFile);
            return pendingCreations
                .computeIfAbsent(objectId, k -> objectCreator.apply(k).cache())
                .doFinally(signal -> pendingCreations.remove(objectId));
          };
        });
  }
}
