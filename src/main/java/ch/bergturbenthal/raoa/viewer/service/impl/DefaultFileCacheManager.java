package ch.bergturbenthal.raoa.viewer.service.impl;

import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.FileCache;
import ch.bergturbenthal.raoa.viewer.service.FileCacheManager;
import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class DefaultFileCacheManager implements FileCacheManager {
  private final ViewerProperties properties;
  private final Map<String, FileCache<?>> existingCaches =
      Collections.synchronizedMap(new HashMap<>());

  public DefaultFileCacheManager(final ViewerProperties properties) {
    this.properties = properties;
  }

  @Override
  public <K> FileCache<K> createCache(
      final String cacheId,
      final Function<K, Mono<File>> objectCreator,
      final Function<K, String> filenameGenerator) {

    final FileCache<K> fileCache =
        (FileCache<K>)
            existingCaches.computeIfAbsent(
                cacheId,
                name -> {
                  final File cacheDir = new File(properties.getCacheDir(), name);
                  if (!cacheDir.exists()) cacheDir.mkdirs();
                  final Map<K, Mono<File>> pendingCreations =
                      Collections.synchronizedMap(new HashMap<>());

                  return (FileCache<K>)
                      objectId -> {
                        final File alreadyCachedFile =
                            new File(cacheDir, filenameGenerator.apply(objectId));
                        if (alreadyCachedFile.exists()) return Mono.just(alreadyCachedFile);
                        return pendingCreations
                            .computeIfAbsent(
                                objectId,
                                k ->
                                    objectCreator
                                        .apply(k)
                                        .map(
                                            createdFile -> {
                                              if (!alreadyCachedFile.getParentFile().exists())
                                                alreadyCachedFile.getParentFile().mkdirs();
                                              createdFile.renameTo(alreadyCachedFile);
                                              return alreadyCachedFile;
                                            })
                                        .cache())
                            .doFinally(signal -> pendingCreations.remove(objectId));
                      };
                });
    return fileCache;
  }
}
