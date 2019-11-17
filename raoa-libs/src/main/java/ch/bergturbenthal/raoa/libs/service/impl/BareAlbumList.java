package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.model.AlbumEntryKey;
import ch.bergturbenthal.raoa.libs.properties.Properties;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.FileImporter;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.Updater;
import ch.bergturbenthal.raoa.libs.service.impl.cache.AlbumEntryKeySerializer;
import ch.bergturbenthal.raoa.libs.service.impl.cache.MetadataSerializer;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.EntryUnit;
import org.ehcache.config.units.MemoryUnit;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

@Slf4j
@Service
public class BareAlbumList implements AlbumList {
  public static final Duration MAX_REPOSITORY_CACHE_TIME = Duration.ofMinutes(5);
  public static final Duration MAX_AUTOINDEX_CACHE_TIME = Duration.ofMinutes(1);
  private static final Collection<String> IMPORTING_TYPES =
      new HashSet<>(Arrays.asList("image/jpeg", "image/tiff", "application/mp4", "video/mp4"));
  private final Mono<SortedMap<Instant, UUID>> autoaddIndex;
  private final Mono<Map<UUID, GitAccess>> repositories;
  private final ExecutorService ioExecutor =
      Executors.newFixedThreadPool(
          50,
          new ThreadFactory() {
            AtomicInteger threadId = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable r) {
              return new Thread(r, "git-thread-" + threadId.incrementAndGet());
            }
          });
  private Properties properties;

  public BareAlbumList(Properties properties, MeterRegistry meterRegistry) {
    this.properties = properties;

    final CacheManager cacheManager =
        CacheManagerBuilder.newCacheManagerBuilder()
            .with(CacheManagerBuilder.persistence(properties.getMetadataCache()))
            .build();
    cacheManager.init();

    final Cache<AlbumEntryKey, Metadata> metadataCache =
        cacheManager.createCache(
            "metadata",
            CacheConfigurationBuilder.newCacheConfigurationBuilder(
                    AlbumEntryKey.class,
                    Metadata.class,
                    ResourcePoolsBuilder.newResourcePoolsBuilder()
                        .heap(2000, EntryUnit.ENTRIES)
                        .disk(3, MemoryUnit.GB, true))
                .withKeySerializer(new AlbumEntryKeySerializer())
                .withValueSerializer(new MetadataSerializer()));

    final Scheduler processScheduler = Schedulers.newElastic("process");
    final Path repoRootPath = this.properties.getRepository().toPath();
    if (!Files.isDirectory(repoRootPath)) {
      try {
        Files.createDirectories(repoRootPath);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    repositories =
        listSubdirs(repoRootPath)
            .publishOn(processScheduler, 5)
            .<GitAccess>map(
                p ->
                    BareGitAccess.accessOf(
                        p,
                        repoRootPath.relativize(p),
                        metadataCache,
                        ioExecutor,
                        processScheduler,
                        meterRegistry))
            .flatMap(p -> p.getMetadata().map(m -> Tuples.of(p, m)))
            .filter(t1 -> t1.getT2().getAlbumId() != null)
            .collectMap(t -> t.getT2().getAlbumId(), Tuple2::getT1)
            .cache(MAX_REPOSITORY_CACHE_TIME);
    autoaddIndex =
        repositories
            .flatMap(
                reps -> {
                  final Mono<SortedMap<Instant, UUID>> collect =
                      Flux.fromIterable(reps.entrySet())
                          .flatMap(
                              e ->
                                  e.getValue()
                                      .readAutoadd()
                                      .map(t -> new AutoaddEntry(t, e.getKey())))
                          .collect(
                              Collectors.toMap(
                                  AutoaddEntry::getTime,
                                  AutoaddEntry::getId,
                                  (a, b) -> b,
                                  TreeMap::new));
                  return collect;
                })
            .cache(MAX_AUTOINDEX_CACHE_TIME);
  }

  private static void listSubdirs(final Path dir, final FluxSink<Path> fluxSink) {
    try {
      Files.list(dir)
          .filter(e -> Files.isReadable(e) && Files.isExecutable(e) && Files.isDirectory(e))
          .forEach(
              d -> {
                if (d.getFileName().toString().endsWith(".git")) {
                  fluxSink.next(d);
                } else {
                  final Path dotGitDir = d.resolve(".git");
                  if (Files.isDirectory(dotGitDir)) {
                    fluxSink.next(dotGitDir);
                  } else {
                    listSubdirs(d, fluxSink);
                  }
                }
              });
    } catch (IOException e) {
      fluxSink.error(e);
    }
  }

  private Flux<Path> listSubdirs(Path dir) {
    return Flux.create(
        fluxSink -> {
          final Future<?> future =
              ioExecutor.submit(
                  () -> {
                    listSubdirs(dir, fluxSink);
                    fluxSink.complete();
                  });
          fluxSink.onDispose(() -> future.cancel(true));
        });
  }

  @Override
  public FileImporter createImporter() {
    return new FileImporter() {
      private final Map<UUID, Mono<Updater>> pendingUpdaters =
          Collections.synchronizedMap(new HashMap<>());

      @Override
      public void close() {
        for (Updater updater :
            Flux.fromIterable(pendingUpdaters.keySet())
                .map(k -> Optional.ofNullable(pendingUpdaters.remove(k)))
                .filter(Optional::isPresent)
                .flatMap(Optional::get)
                .toIterable()) {
          updater.close();
        }
      }

      public Mono<Boolean> importFile(final Path file) {
        return repositories
            .flatMap(
                reps ->
                    Mono.create(
                        (MonoSink<Mono<Boolean>> monoSink) -> {
                          final Future<?> submit =
                              ioExecutor.submit(
                                  () -> {
                                    try {
                                      AutoDetectParser parser = new AutoDetectParser();
                                      BodyContentHandler handler = new BodyContentHandler();
                                      Metadata metadata = new Metadata();
                                      final TikaInputStream inputStream = TikaInputStream.get(file);
                                      parser.parse(inputStream, handler, metadata);
                                      if (!IMPORTING_TYPES.contains(
                                          metadata.get(Metadata.CONTENT_TYPE))) {
                                        log.info(
                                            "Unsupported content type: "
                                                + metadata.get(Metadata.CONTENT_TYPE));
                                        monoSink.success(Mono.just(false));
                                        return;
                                      }
                                      final Date createDate =
                                          metadata.getDate(TikaCoreProperties.CREATED);
                                      if (createDate == null) {
                                        log.info("No creation timestamp");
                                        monoSink.success(Mono.just(false));
                                        return;
                                      }
                                      final Instant createTimestamp = createDate.toInstant();

                                      final String prefix =
                                          DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
                                              .format(
                                                  createTimestamp.atZone(ZoneId.systemDefault()));
                                      final String targetFilename =
                                          prefix + "-" + file.getFileName().toString();
                                      monoSink.success(
                                          albumOf(createTimestamp)
                                              .flatMap(
                                                  repositoryId -> {
                                                    ioExecutor.submit(
                                                        () -> {
                                                          getAlbum(repositoryId)
                                                              .flatMap(GitAccess::getName)
                                                              .defaultIfEmpty("not found")
                                                              .subscribe(
                                                                  name ->
                                                                      log.info(
                                                                          "Import " + file + " to "
                                                                              + name));
                                                        });

                                                    return pendingUpdaters.computeIfAbsent(
                                                        repositoryId,
                                                        k -> reps.get(k).createUpdater().cache());
                                                  })
                                              .flatMap(
                                                  updater ->
                                                      updater
                                                          .importFile(file, targetFilename)
                                                          .onErrorResume(
                                                              e -> {
                                                                log.warn(
                                                                    "Cannot import file " + file,
                                                                    e);
                                                                return Mono.just(false);
                                                              }))
                                              .defaultIfEmpty(false));
                                    } catch (TikaException | SAXException | IOException e) {
                                      log.warn("Cannot access file " + file, e);
                                      monoSink.success(Mono.just(false));
                                    }
                                  });
                          monoSink.onCancel(() -> submit.cancel(true));
                        }))
            .flatMap(Function.identity());
      }

      @Override
      public Mono<Boolean> commitAll() {
        return Flux.fromIterable(pendingUpdaters.values())
            .flatMap(m -> m)
            .flatMap(Updater::commit)
            .reduce((b1, b2) -> b1 && b2)
            .defaultIfEmpty(Boolean.TRUE)
            .doFinally(signal -> pendingUpdaters.clear());
      }
    };
  }

  @Override
  public Flux<FoundAlbum> listAlbums() {
    return repositories
        .flatMapIterable(Map::entrySet)
        .map(e -> new FoundAlbum(e.getKey(), e.getValue()));
  }

  @Override
  public Mono<GitAccess> getAlbum(final UUID albumId) {
    return repositories
        .map(reps -> Optional.ofNullable(reps.get(albumId)))
        .filter(Optional::isPresent)
        .map(Optional::get);
  }

  private Mono<UUID> albumOf(final Instant timestamp) {
    return autoaddIndex
        .map(i -> i.headMap(timestamp))
        .filter(t -> !t.isEmpty())
        .map(t -> t.get(t.lastKey()));
  }

  @Value
  private static class AutoaddEntry {
    private Instant time;
    private UUID id;
  }
}
