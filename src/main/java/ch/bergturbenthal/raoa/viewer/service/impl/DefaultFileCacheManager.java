package ch.bergturbenthal.raoa.viewer.service.impl;

import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.FileCache;
import ch.bergturbenthal.raoa.viewer.service.FileCacheManager;
import java.io.File;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Mono;

@Service
public class DefaultFileCacheManager implements FileCacheManager {
  private final ViewerProperties properties;
  private final Map<String, FileCacheEntry<?>> existingCaches =
      Collections.synchronizedMap(new HashMap<>());

  public DefaultFileCacheManager(final ViewerProperties properties) {
    this.properties = properties;
  }

  @Override
  public <K> FileCache<K> createCache(
      final String cacheId,
      final BiFunction<K, File, Mono<File>> objectCreator,
      final Function<K, String> filenameGenerator) {

    return (FileCache<K>)
        existingCaches.computeIfAbsent(
            cacheId,
            name ->
                new FileCacheEntry<>(
                    new File(properties.getCacheDir(), name), filenameGenerator, objectCreator));
  }

  @Scheduled(fixedDelay = 60 * 1000)
  public void reduceCache() {
    final DataSize defaultCacheSize = properties.getDefaultCacheSize();
    existingCaches.forEach(
        (name, cache) -> {
          synchronized (cache) {
            final long maxCacheSize =
                properties.getCacheSize().getOrDefault(name, defaultCacheSize).toBytes();
            long currentCacheSize = cache.diskUsage();
            if (currentCacheSize > maxCacheSize) {
              cache.removeFiles(currentCacheSize - maxCacheSize);
            }
          }
        });
  }

  @Value
  private static class FileEntryMetadata {
    private File file;
    private AtomicReference<Instant> lastUse = new AtomicReference<>(Instant.MIN);
    private Mono<File> cachedFile;
  }

  private static class FileCacheEntry<K> implements FileCache<K> {
    private final File cacheDir;
    private final Function<K, String> filenameGenerator;
    private final Map<K, FileEntryMetadata> cacheEntries =
        Collections.synchronizedMap(new HashMap<>());
    private final BiFunction<K, File, Mono<File>> objectCreator;

    public FileCacheEntry(
        final File cacheDir,
        final Function<K, String> filenameGenerator,
        final BiFunction<K, File, Mono<File>> objectCreator) {
      this.cacheDir = cacheDir;
      this.filenameGenerator = filenameGenerator;
      this.objectCreator = objectCreator;
      if (!cacheDir.exists()) cacheDir.mkdirs();
    }

    public synchronized void removeFiles(long removeByteCount) {
      final File[] listFiles = cacheDir.listFiles();
      if (listFiles == null) return;
      long remainingByteCount = removeByteCount;
      final Set<File> managedFiles =
          cacheEntries.values().stream()
              .map(FileEntryMetadata::getFile)
              .collect(Collectors.toSet());

      final Iterator<File> existingFiles = Arrays.asList(listFiles).iterator();
      while (remainingByteCount > 0 && existingFiles.hasNext()) {
        final File candidate = existingFiles.next();
        if (managedFiles.contains(candidate)) continue;
        if (candidate.lastModified() > System.currentTimeMillis() - 10000) continue;
        final long fileSize = candidate.length();
        if (candidate.delete()) remainingByteCount -= fileSize;
      }
      final Collection<K> filesToRemove = new ArrayList<>();
      final Iterator<Map.Entry<K, FileEntryMetadata>> candidateIterator =
          cacheEntries.entrySet().stream()
              .filter(e -> !e.getValue().getLastUse().get().equals(Instant.MIN))
              .sorted(Comparator.comparing(e -> e.getValue().getLastUse().get()))
              .iterator();
      while (remainingByteCount > 0 && candidateIterator.hasNext()) {
        final Map.Entry<K, FileEntryMetadata> nextCandidate = candidateIterator.next();
        cacheEntries.remove(nextCandidate.getKey());
        final File file = nextCandidate.getValue().getFile();
        final long length = file.length();
        if (file.delete()) {
          remainingByteCount -= length;
        }
      }
    }

    public synchronized long diskUsage() {
      final File[] listFiles = cacheDir.listFiles(File::isFile);
      if (listFiles == null) return 0;
      return Arrays.stream(listFiles).mapToLong(File::length).sum();
    }

    @Override
    public synchronized Mono<File> take(final K objectId, Predicate<File> validate) {

      final FileEntryMetadata existingEntry = cacheEntries.get(objectId);
      if (existingEntry != null) {
        final File existingEntryFile = existingEntry.getFile();
        if (existingEntryFile.exists()) {
          if (validate.test(existingEntryFile)) {
            existingEntry.getLastUse().set(Instant.now());
            return Mono.just(existingEntryFile);
          }
        } else {
          existingEntry
              .getCachedFile()
              .flatMap(
                  f -> {
                    if (validate.test(f)) return Mono.just(f);
                    else return take(objectId, validate);
                  });
        }
      }

      final File targetFilename = new File(cacheDir, filenameGenerator.apply(objectId));
      if (targetFilename.exists() && validate.test(targetFilename)) {
        return cacheEntries
            .computeIfAbsent(
                objectId,
                k -> {
                  final FileEntryMetadata entryMetadata =
                      new FileEntryMetadata(targetFilename, Mono.just(targetFilename));
                  entryMetadata.getLastUse().set(Instant.now());
                  return entryMetadata;
                })
            .getCachedFile();
      }
      final FileEntryMetadata fileEntryMetadata =
          cacheEntries.computeIfAbsent(
              objectId,
              k ->
                  new FileEntryMetadata(
                      targetFilename,
                      objectCreator
                          .apply(k, cacheDir)
                          .map(
                              createdFile -> {
                                createdFile.renameTo(targetFilename);
                                return targetFilename;
                              })
                          .cache()));
      return fileEntryMetadata
          .getCachedFile()
          .doFinally(signal -> fileEntryMetadata.getLastUse().set(Instant.now()));
    }
  }
}
