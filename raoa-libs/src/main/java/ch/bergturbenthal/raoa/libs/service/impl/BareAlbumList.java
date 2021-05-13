package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.model.AlbumMeta;
import ch.bergturbenthal.raoa.libs.properties.Properties;
import ch.bergturbenthal.raoa.libs.service.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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
  private final AtomicReference<CachedData> scanCache = new AtomicReference<>();
  /*private final ExecutorService ioExecutor =
  Executors.newFixedThreadPool(
      50,
      new ThreadFactory() {
        AtomicInteger threadId = new AtomicInteger();

        @Override
        public Thread newThread(final Runnable r) {
          return new Thread(r, "git-thread-" + threadId.incrementAndGet());
        }
      });*/
  private final AsyncService asyncService;
  private final MeterRegistry meterRegistry;
  private final Scheduler processScheduler;
  private final Path repoRootPath;
  private final Properties properties;

  public BareAlbumList(
      Properties properties, MeterRegistry meterRegistry, final AsyncService asyncService) {
    this.properties = properties;
    this.asyncService = asyncService;

    processScheduler = Schedulers.newElastic("process");
    repoRootPath = this.properties.getRepository().toPath();
    if (!Files.isDirectory(repoRootPath)) {
      try {
        Files.createDirectories(repoRootPath);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    this.meterRegistry = meterRegistry;
    resetCache();
  }

  private static void listSubdirs(final Path dir, final Consumer<Path> fluxSink)
      throws IOException {
    final Stream<Path> pathStream =
        Files.list(dir)
            .filter(e -> Files.isReadable(e) && Files.isExecutable(e) && Files.isDirectory(e));
    for (Path d : (Iterable<Path>) pathStream::iterator) {
      if (d.getFileName().toString().endsWith(".git")) {
        fluxSink.accept(d);
      } else {
        final Path dotGitDir = d.resolve(".git");
        if (Files.isDirectory(dotGitDir)) {
          fluxSink.accept(dotGitDir);
        } else {
          listSubdirs(d, fluxSink);
        }
      }
    }
  }

  @Override
  public void resetCache() {
    final Mono<Map<UUID, GitAccess>> repositories =
        listSubdirs(repoRootPath)
            .publishOn(processScheduler, 5)
            .<GitAccess>map(
                p ->
                    BareGitAccess.accessOf(
                        p,
                        repoRootPath.relativize(p),
                        asyncService,
                        processScheduler,
                        meterRegistry))
            .flatMap(p -> p.getMetadata().map(m -> Tuples.of(p, m)), 4)
            .filter(t1 -> t1.getT2().getAlbumId() != null)
            .collectMap(t -> t.getT2().getAlbumId(), Tuple2::getT1)
            .cache(MAX_REPOSITORY_CACHE_TIME);
    final Mono<SortedMap<Instant, UUID>> autoaddIndex =
        repositories
            .<SortedMap<Instant, UUID>>flatMap(
                reps ->
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
                                TreeMap::new)))
            .cache(MAX_AUTOINDEX_CACHE_TIME);
    scanCache.set(new CachedData(autoaddIndex, repositories));
  }

  private Flux<Path> listSubdirs(Path dir) {
    return asyncService.asyncFlux(sink -> listSubdirs(dir, sink));
  }

  @Override
  public FileImporter createImporter(final Updater.CommitContext context) {
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

      public @NotNull Mono<Tuple2<UUID, ObjectId>> importFile(final Path file) {
        final String originalFileName = file.getFileName().toString();
        return importFile(file, originalFileName);
      }

      public @NotNull Mono<Tuple2<UUID, ObjectId>> importFile(
          final Path file, final String originalFileName) {
        return importFile(file, originalFileName, id -> Mono.just(true));
      }

      public @NotNull Mono<Tuple2<UUID, ObjectId>> importFile(
          final Path file,
          final String originalFileName,
          Function<UUID, Mono<Boolean>> albumFilter) {
        return scanCache
            .get()
            .getRepositories()
            .flatMap(
                reps ->
                    asyncService.<Mono<Tuple2<UUID, ObjectId>>>asyncMono(
                        () -> {
                          try {
                            final Optional<Instant> foundTimestamp = detectTimestamp(file);
                            if (foundTimestamp.isEmpty()) return Mono.empty();

                            final Instant createTimestamp = foundTimestamp.get();

                            final String prefix =
                                DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
                                    .format(createTimestamp.atZone(ZoneId.systemDefault()));
                            final String targetFilename = prefix + "-" + originalFileName;
                            return albumOf(createTimestamp)
                                .flatMap(
                                    repositoryId ->
                                        getAlbum(repositoryId)
                                            .flatMap(GitAccess::getName)
                                            .defaultIfEmpty("not found")
                                            .doOnNext(
                                                name -> log.info("Import " + file + " to " + name))
                                            .map(name -> repositoryId))
                                .filterWhen(albumFilter)
                                .flatMap(
                                    repositoryId ->
                                        pendingUpdaters
                                            .computeIfAbsent(
                                                repositoryId,
                                                k -> reps.get(k).createUpdater().cache())
                                            .flatMap(
                                                updater ->
                                                    updater
                                                        .importFile(file, targetFilename)
                                                        .map(
                                                            objectId ->
                                                                Tuples.of(repositoryId, objectId))
                                                        .onErrorResume(
                                                            e -> {
                                                              log.warn(
                                                                  "Cannot import file " + file, e);
                                                              return Mono.empty();
                                                            })));
                          } catch (TikaException | SAXException | IOException e) {
                            log.warn("Cannot access file " + file, e);
                            return (Mono.empty());
                          }
                        }))
            .flatMap(Function.identity());
      }

      @Override
      public @NotNull Mono<Boolean> commitAll() {
        return Flux.fromIterable(pendingUpdaters.values())
            .flatMap(m -> m)
            .flatMap((Updater updater) -> updater.commit(context))
            .reduce((b1, b2) -> b1 && b2)
            .defaultIfEmpty(Boolean.TRUE)
            .doFinally(signal -> pendingUpdaters.clear())
            .doFinally(signal -> resetCache());
      }
    };
  }

  @Override
  public Mono<UUID> detectTargetAlbum(final Path file) {
    return scanCache
        .get()
        .getRepositories()
        .flatMap(reps -> asyncService.asyncMonoOptional(() -> detectTimestamp(file)))
        .flatMap(this::albumOf);
  }

  private Optional<Instant> detectTimestamp(final Path file)
      throws IOException, SAXException, TikaException {
    AutoDetectParser parser = new AutoDetectParser();
    BodyContentHandler handler = new BodyContentHandler();
    Metadata metadata = new Metadata();
    final TikaInputStream inputStream = TikaInputStream.get(file);
    parser.parse(inputStream, handler, metadata);
    if (!IMPORTING_TYPES.contains(metadata.get(Metadata.CONTENT_TYPE))) {
      log.info("Unsupported content type: " + metadata.get(Metadata.CONTENT_TYPE));
      return Optional.empty();
    }
    final Date createDate = metadata.getDate(TikaCoreProperties.CREATED);
    if (createDate == null) {
      log.info("No creation timestamp");
      return Optional.empty();
    }
    final Instant createTimestamp = createDate.toInstant();
    return Optional.of(createTimestamp);
  }

  @Override
  public Flux<FoundAlbum> listAlbums() {
    return scanCache
        .get()
        .getRepositories()
        .flatMapIterable(Map::entrySet)
        .map(e -> new FoundAlbum(e.getKey(), e.getValue()));
  }

  @Override
  public Flux<String> listParentDirs() {
    return scanCache
        .get()
        .getRepositories()
        .flatMapIterable(Map::values)
        .flatMap(GitAccess::getFullPath)
        .map(
            f -> {
              final int i = f.lastIndexOf('/');
              if (i < 0) return f;
              return f.substring(0, i);
            })
        .distinct();
  }

  @Override
  public Mono<GitAccess> getAlbum(final UUID albumId) {
    return scanCache
        .get()
        .getRepositories()
        .map(reps -> Optional.ofNullable(reps.get(albumId)))
        .filter(Optional::isPresent)
        .map(Optional::get);
  }

  @Override
  public Mono<UUID> createAlbum(final List<String> albumPath) {
    return asyncService
        .asyncMono(
            () -> {
              File dir = properties.getRepository();
              for (String name : albumPath) {
                dir = new File(dir, name);
              }
              dir = new File(dir.getParent(), dir.getName() + ".git");
              if (!dir.exists()) {
                Git.init().setDirectory(dir).setBare(true).call();
              }
              return dir;
            })
        .map(File::toPath)
        .flatMap(
            p ->
                BareGitAccess.accessOf(
                        p,
                        repoRootPath.relativize(p),
                        asyncService,
                        processScheduler,
                        meterRegistry)
                    .getMetadata()
                    .map(AlbumMeta::getAlbumId)
                    .doOnNext(signal -> resetCache()));
  }

  private Mono<UUID> albumOf(final Instant timestamp) {
    return scanCache
        .get()
        .getAutoaddIndex()
        .map(i -> i.headMap(timestamp))
        .filter(t -> !t.isEmpty())
        .map(t -> t.get(t.lastKey()));
  }

  @Value
  private static class AutoaddEntry {
    Instant time;
    UUID id;
  }

  @Value
  private static class CachedData {
    Mono<SortedMap<Instant, UUID>> autoaddIndex;
    Mono<Map<UUID, GitAccess>> repositories;
  }
}
