package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.model.AlbumMeta;
import ch.bergturbenthal.raoa.libs.properties.Properties;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.AsyncService;
import ch.bergturbenthal.raoa.libs.service.FileImporter;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.Updater;
import ch.bergturbenthal.raoa.libs.util.TikaUtil;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class BareAlbumList implements AlbumList {
    public static final Duration MAX_REPOSITORY_CACHE_TIME = Duration.ofMinutes(5);
    public static final Duration MAX_AUTOINDEX_CACHE_TIME = Duration.ofMinutes(5);
    private static final Collection<String> IMPORTING_TYPES = new HashSet<>(
            Arrays.asList("image/jpeg", "image/tiff", "application/mp4", "video/mp4"));
    private final AtomicReference<CachedData> scanCache = new AtomicReference<>();
    /*
     * private final ExecutorService ioExecutor = Executors.newFixedThreadPool( 50, new ThreadFactory() { AtomicInteger
     * threadId = new AtomicInteger();
     * @Override public Thread newThread(final Runnable r) { return new Thread(r, "git-thread-" +
     * threadId.incrementAndGet()); } });
     */
    private final AsyncService asyncService;
    private final MeterRegistry meterRegistry;
    private final Scheduler processScheduler;
    private final Path repoRootPath;
    private final Properties properties;

    public BareAlbumList(Properties properties, MeterRegistry meterRegistry, final AsyncService asyncService) {
        this.properties = properties;
        this.asyncService = asyncService;

        processScheduler = Schedulers.newBoundedElastic(2, 300, "process");
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

    private static void listSubdirs(final Path dir, final Consumer<Path> fluxSink) throws IOException {
        final Stream<Path> pathStream = Files.list(dir)
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

        final Mono<Map<UUID, GitAccess>> repositories = listSubdirs(repoRootPath)
                .<GitAccess> map(p -> BareGitAccess.accessOf(p, repoRootPath.relativize(p), asyncService,
                        processScheduler, meterRegistry))
                .flatMap(p -> p.getMetadata().map(m -> Tuples.of(p, m)), 4)
                .filter(t1 -> t1.getT2().getAlbumId() != null).collectMap(t -> t.getT2().getAlbumId(), Tuple2::getT1)
                .cache(MAX_REPOSITORY_CACHE_TIME);
        final Mono<SortedMap<Instant, UUID>> autoaddIndex = repositories.<SortedMap<Instant, UUID>> flatMap(reps -> Flux
                .fromIterable(reps.entrySet())
                .flatMap(e -> e.getValue().readAutoadd().map(t -> new AutoaddEntry(t, e.getKey())), 10)
                .collect(Collectors.toMap(AutoaddEntry::getTime, AutoaddEntry::getId, (a, b) -> b, TreeMap::new)))
                .cache(MAX_AUTOINDEX_CACHE_TIME);
        scanCache.set(new CachedData(autoaddIndex, repositories));
    }

    private Flux<Path> listSubdirs(Path dir) {
        return asyncService.asyncFlux(sink -> {
            final Path metaDir = dir.resolve(".meta.git");
            while (!Files.exists(metaDir)) {
                log.info("Waiting for " + metaDir);
                Thread.sleep(100);
            }
            listSubdirs(dir, sink);
        });
    }

    @Override
    public FileImporter createImporter(final Updater.CommitContext context) {
        return new FileImporter() {
            private final Map<UUID, Mono<Updater>> pendingUpdaters = Collections.synchronizedMap(new HashMap<>());

            @Override
            public Mono<Void> close() {
                return Flux.fromIterable(pendingUpdaters.keySet())
                        .map(k -> Optional.ofNullable(pendingUpdaters.remove(k))).filter(Optional::isPresent)
                        .flatMap(Optional::get, 3).flatMap(Updater::close).then();
            }

            public @NotNull Mono<Tuple2<UUID, ObjectId>> importFile(final Path file) {
                final String originalFileName = file.getFileName().toString();
                return importFile(file, originalFileName);
            }

            public @NotNull Mono<Tuple2<UUID, ObjectId>> importFile(final Path file, final String originalFileName) {
                return importFile(file, originalFileName, id -> Mono.just(true));
            }

            public @NotNull Mono<Tuple2<UUID, ObjectId>> importFile(final Path file, final String originalFileName,
                    Function<UUID, Mono<Boolean>> albumFilter) {
                return doImportFile(file, originalFileName, albumFilter, createTimestamp -> albumOf(createTimestamp));
            }

            @Override
            public Mono<Tuple2<UUID, ObjectId>> importFileIntoRepository(final Path file, final String originalFileName,
                    final UUID selectedRepository) {
                return doImportFile(file, originalFileName, album -> Mono.just(true),
                        date -> Mono.just(selectedRepository));
            }

            @NotNull
            private Mono<Tuple2<UUID, ObjectId>> doImportFile(final Path file, final String originalFileName,
                    final Function<UUID, Mono<Boolean>> albumFilter,
                    final Function<Instant, Mono<UUID>> repositorySelector) {
                return scanCache.get().getRepositories().flatMap(
                        reps -> asyncService.asyncMonoOptional(() -> detectTimestamp(file)).flatMap(createTimestamp -> {
                            final String prefix = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
                                    .format(createTimestamp.atZone(properties.getTimeZone().toZoneId()));
                            final String targetFilename = prefix + "-" + originalFileName;
                            return repositorySelector.apply(createTimestamp).filterWhen(albumFilter)
                                    .flatMap(repositoryId -> pendingUpdaters
                                            .computeIfAbsent(repositoryId, k -> reps.get(k).createUpdater().cache())
                                            .flatMap(updater -> updater.importFile(file, targetFilename, true)
                                                    .map(objectId -> Tuples.of(repositoryId, objectId))
                                                    .onErrorResume(e -> {
                                                        log.warn("Cannot import file " + file, e);
                                                        return Mono.empty();
                                                    })));
                        }));
            }

            @Override
            public @NotNull Mono<Boolean> commitAll() {
                return Flux.fromIterable(pendingUpdaters.values()).flatMap(m -> m)
                        .flatMap((Updater updater) -> updater.commit(context)).reduce((b1, b2) -> b1 && b2)
                        .defaultIfEmpty(Boolean.TRUE).doFinally(signal -> pendingUpdaters.clear())
                        .doFinally(signal -> resetCache());
            }
        };
    }

    @Override
    public Mono<UUID> detectTargetAlbum(final Path file) {
        return scanCache.get().getRepositories()
                .flatMap(reps -> asyncService.asyncMonoOptional(() -> detectTimestamp(file))).flatMap(this::albumOf);
    }

    private Optional<Instant> detectTimestamp(final Path file) throws IOException, SAXException, TikaException {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler();
        Metadata metadata = new Metadata();
        final TikaInputStream inputStream = TikaInputStream.get(file);
        try {
            parser.parse(inputStream, handler, metadata);
        } catch (Throwable e) {
            log.error("Error while parsing file {}", file, e);
            return Optional.empty();
        }
        final String mediaType = metadata.get(Metadata.CONTENT_TYPE);
        if (!IMPORTING_TYPES.contains(mediaType)) {
            log.info("Unsupported content type: " + mediaType);
            return Optional.empty();
        }
        return TikaUtil.extractCreateTime(metadata);
    }

    @Override
    public Flux<FoundAlbum> listAlbums() {
        return scanCache.get().getRepositories().flatMapIterable(Map::entrySet)
                .map(e -> new FoundAlbum(e.getKey(), e.getValue()));
    }

    @Override
    public Flux<String> listParentDirs() {
        return scanCache.get().getRepositories().flatMapIterable(Map::values).flatMap(GitAccess::getFullPath, 2)
                .map(f -> {
                    final int i = f.lastIndexOf('/');
                    if (i < 0)
                        return f;
                    return f.substring(0, i);
                }).distinct();
    }

    @Override
    public Mono<GitAccess> getAlbum(final UUID albumId) {
        return scanCache.get().getRepositories().map(reps -> Optional.ofNullable(reps.get(albumId)))
                .filter(Optional::isPresent).map(Optional::get);
    }

    @Override
    public Mono<UUID> createAlbum(final List<String> albumPath) {
        return asyncService.asyncMono(() -> {
            File dir = properties.getRepository();
            for (String name : albumPath) {
                dir = new File(dir, name);
            }
            dir = new File(dir.getParent(), dir.getName() + ".git");
            if (!dir.exists()) {
                Git.init().setDirectory(dir).setBare(true).call();
            }
            return dir;
        }).map(File::toPath)
                .flatMap(p -> BareGitAccess
                        .accessOf(p, repoRootPath.relativize(p), asyncService, processScheduler, meterRegistry)
                        .getMetadata().map(AlbumMeta::getAlbumId).doOnNext(signal -> resetCache()));
    }

    private Mono<UUID> albumOf(final Instant timestamp) {
        return scanCache.get().getAutoaddIndex().map(i -> i.headMap(timestamp)).filter(t -> !t.isEmpty())
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
