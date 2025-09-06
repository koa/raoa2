package ch.bergturbenthal.raoa.coordinator.service.impl;

import ch.bergturbenthal.raoa.coordinator.model.CoordinatorProperties;
import ch.bergturbenthal.raoa.coordinator.service.RemoteMediaProcessor;
import ch.bergturbenthal.raoa.elastic.model.AlbumData;
import ch.bergturbenthal.raoa.elastic.model.AlbumEntryData;
import ch.bergturbenthal.raoa.elastic.model.CommitJob;
import ch.bergturbenthal.raoa.elastic.repository.AlbumDataEntryRepository;
import ch.bergturbenthal.raoa.elastic.repository.AlbumDataRepository;
import ch.bergturbenthal.raoa.elastic.repository.CommitJobRepository;
import ch.bergturbenthal.raoa.elastic.service.impl.AlbumStatisticsCollector;
import ch.bergturbenthal.raoa.elastic.service.impl.ElasticSearchDataViewService;
import ch.bergturbenthal.raoa.libs.model.AlbumMeta;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.AsyncService;
import ch.bergturbenthal.raoa.libs.service.FileImporter;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.ThumbnailFilenameService;
import ch.bergturbenthal.raoa.libs.service.Updater;
import ch.bergturbenthal.raoa.libs.service.UploadFilenameService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.map.LRUMap;
import org.eclipse.jgit.lib.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.elasticsearch.BulkFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.function.TupleUtils;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuple5;
import reactor.util.function.Tuples;
import reactor.util.retry.Retry;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class Poller {

    private final AlbumList albumList;
    private final ElasticSearchDataViewService elasticSearchDataViewService;
    private final ThumbnailFilenameService thumbnailFilenameService;
    private final RemoteMediaProcessor remoteMediaProcessor;
    private final AlbumDataEntryRepository albumDataEntryRepository;
    private final AlbumDataRepository albumDataRepository;
    private final AsyncService asyncService;
    private final CoordinatorProperties coordinatorProperties;
    private final MeterRegistry meterRegistry;
    private final Map<File, Mono<Set<File>>> existingFilesCache = Collections.synchronizedMap(new LRUMap<>(50));
    private final CommitJobRepository commitJobRepository;
    private final UploadFilenameService uploadFilenameService;

    private final Scheduler pollerScheduler = Schedulers.boundedElastic();
    private final Mono<Void> resetJobStates;

    public Poller(final AlbumList albumList, final ElasticSearchDataViewService elasticSearchDataViewService,
            final ThumbnailFilenameService thumbnailFilenameService, final RemoteMediaProcessor remoteMediaProcessor,
            final AlbumDataEntryRepository albumDataEntryRepository, final AlbumDataRepository albumDataRepository,
            final AsyncService asyncService, final CoordinatorProperties coordinatorProperties,
            final MeterRegistry meterRegistry, final CommitJobRepository commitJobRepository,
            final UploadFilenameService uploadFilenameService) {
        this.albumList = albumList;
        this.elasticSearchDataViewService = elasticSearchDataViewService;
        this.thumbnailFilenameService = thumbnailFilenameService;
        this.remoteMediaProcessor = remoteMediaProcessor;
        this.albumDataEntryRepository = albumDataEntryRepository;
        this.albumDataRepository = albumDataRepository;
        this.asyncService = asyncService;
        this.coordinatorProperties = coordinatorProperties;
        this.meterRegistry = meterRegistry;
        this.commitJobRepository = commitJobRepository;
        this.uploadFilenameService = uploadFilenameService;
        resetJobStates = resetJobStates().cache();
    }

    private Tuple2<Integer, String> batchSizeByFilename(final String nameString) {
        final String lowerFilename = nameString.toLowerCase(Locale.ROOT);
        if (lowerFilename.endsWith(".jpg"))
            return Tuples.of(1000, "");
        if (lowerFilename.endsWith(".nef"))
            return Tuples.of(100, "");
        if (lowerFilename.endsWith(".mp4"))
            return Tuples.of(1, coordinatorProperties.getVideoResource());
        if (lowerFilename.endsWith(".mkv"))
            return Tuples.of(1, coordinatorProperties.getVideoResource());
        return Tuples.of(0, "");
    }

    @Scheduled(fixedDelay = 5 * 1000, initialDelay = 500)
    public void updateUsers() {
        try {
            elasticSearchDataViewService.updateUserData().block(Duration.ofMinutes(3));
        } catch (Exception ex) {
            log.warn("Error updating users", ex);
        }
    }

    @Scheduled(fixedDelay = 7 * 1000, initialDelay = 1000)
    public void poll() {
        // log.info("scheduler started");
        while (true) {
            // log.info("poll cycle started");
            final int concurrentProcessingAlbums = coordinatorProperties.getConcurrentProcessingAlbums();
            // log.info("Concurrency: " + concurrentProcessingAlbums);
            // Set<UUID> pendingAlbums = Collections.synchronizedSet(new HashSet<>());
            final Mono<Long> removedRepos = albumList.listAlbums()
                    // .log("album-in")
                    .flatMap(album -> Mono
                            .zip(album.getAccess().getCurrentVersion()
                                    .doOnError(ex -> log.warn("Cannot load current version", ex)),
                                    albumDataRepository.findById(album.getAlbumId())
                                            .map(albumData1 -> Optional.ofNullable(albumData1.getCurrentVersion()))
                                            .defaultIfEmpty(Optional.empty())
                                            .doOnError(ex -> log.warn("Cannot load version from ES", ex)))
                            // .doOnError(ex -> log.warn("Cannot zip", ex))
                            .map(t -> Tuples.of(Optional.of(t.getT1()),
                                    !t.getT2().map(v -> v.equals(t.getT1())).orElse(false)))
                            .defaultIfEmpty(Tuples.of(Optional.empty(), true)).onErrorResume(ex1 -> {
                                log.warn("Cannot check album " + album.getAccess(), ex1);
                                return Mono.just(Tuples.of(Optional.empty(), true));
                            }).timeout(Duration.ofSeconds(60)).retryWhen(Retry.backoff(10, Duration.ofSeconds(10)))
                            .map(touched -> Tuples.of(album, touched.getT1(), touched.getT2())), 10)
                    // .log("album in")
                    .flatMap(albumData -> {
                        boolean touched = albumData.getT3();
                        final Optional<ObjectId> newVersion = albumData.getT2();
                        if (!touched)
                            return Mono.just(albumData.getT1().getAlbumId());
                        final AlbumList.FoundAlbum album = albumData.getT1();
                        log.info("Start " + album);
                        final UUID albumId = album.getAlbumId();

                        // pendingAlbums.add(albumId);
                        // log.info("Pending: " + pendingAlbums);
                        Mono<Map<ObjectId, AlbumEntryData>> mapMono = albumDataEntryRepository.findByAlbumId(albumId)
                                // .log("entry before")
                                .collectMap(AlbumEntryData::getEntryId)
                                .retryWhen(Retry.backoff(10, Duration.ofSeconds(10)));
                        return mapMono.flatMap(existingEntries -> album.getAccess()
                                .listFiles(ElasticSearchDataViewService.XMP_FILE_FILTER).collectMap(fe -> {
                                    final String filename = fe.getNameString();
                                    return filename.substring(0, filename.length() - 4);
                                }, GitAccess.GitFileEntry::getFileId).flatMap(xmpFiles -> {
                                    final Flux<GitAccess.GitFileEntry> allCurrentMediaFiles = album.getAccess()
                                            .listFiles(ElasticSearchDataViewService.MEDIA_FILE_FILTER)
                                            .filterWhen(gitFileEntry -> isValidEntry(album, gitFileEntry), 5);

                                    return allCurrentMediaFiles.map(GitAccess.GitFileEntry::getFileId)
                                            .collect(Collectors.toUnmodifiableSet())
                                            .flatMap(currentValidMediaFiles -> albumDataEntryRepository.deleteAll(Flux
                                                    .fromIterable(existingEntries.entrySet())
                                                    .filter(storedEntry -> !currentValidMediaFiles
                                                            .contains(storedEntry.getKey()))
                                                    .map(Map.Entry::getValue)))
                                            .then(allCurrentMediaFiles
                                                    .map(gitFileEntry1 -> Tuples.of(gitFileEntry1,
                                                            Optional.ofNullable(
                                                                    existingEntries.get(gitFileEntry1.getFileId()))))
                                                    .filterWhen(gitFileEntry1 -> entryAlreadyProcessed(album,
                                                            gitFileEntry1.getT1(), gitFileEntry1.getT2(),
                                                            Optional.ofNullable(xmpFiles
                                                                    .get(gitFileEntry1.getT1().getNameString())))
                                                                            .map(b -> !b))
                                                    .map(Tuple2::getT1).map(GitAccess.GitFileEntry::getNameString)
                                                    .map(filename -> Tuples.of(filename, batchSizeByFilename(filename)))
                                                    .filter(t -> t.getT2().getT1() > 0).groupBy(Tuple2::getT2)
                                                    .flatMap(group -> group.map(Tuple2::getT1)
                                                            .buffer(group.key().getT1())
                                                            .map(batch -> Tuples.of(batch, group.key().getT2())))
                                                    .flatMap(batch -> remoteMediaProcessor
                                                            .processFiles(albumId, batch.getT1(), batch.getT2())
                                                            .doOnNext(ok -> {
                                                                if (!ok) {
                                                                    log.warn("Error processing Files on " + albumId);
                                                                    batch.getT1().forEach(
                                                                            filename -> log.info("- " + filename));
                                                                } else
                                                                    log.info("Processed " + batch.getT1().size()
                                                                            + " files on " + albumId);
                                                            }), coordinatorProperties.getConcurrentProcessingImages())
                                                    .all(ok -> ok))
                                            .filter(ok -> ok).flatMap(allFilesProcessed -> {
                                                final Mono<String> nameMono = album.getAccess().getName();
                                                final Mono<ObjectId> versionMono = album.getAccess()
                                                        .getCurrentVersion();
                                                final Mono<AlbumMeta> metadataMono = album.getAccess().getMetadata();
                                                Mono<Tuple5<AlbumStatisticsCollector, String, ObjectId, AlbumMeta, Optional<ObjectId>>> tuple5Mono = Mono
                                                        .zip(nameMono, versionMono, metadataMono)
                                                        .flatMap(TupleUtils.function(
                                                                (name, version, metadata) -> albumDataEntryRepository
                                                                        .findByAlbumId(albumId).publish(flux -> {
                                                                            String titleEntry = metadata
                                                                                    .getTitleEntry();
                                                                            Mono<ObjectId> titleEntryId = titleEntry != null
                                                                                    ? flux.filter(e -> Objects.equals(
                                                                                            e.getFilename(),
                                                                                            titleEntry)).next()
                                                                                            .map(AlbumEntryData::getEntryId)
                                                                                    : Mono.empty();
                                                                            Mono<AlbumStatisticsCollector> collect = flux
                                                                                    .collect(
                                                                                            () -> new AlbumStatisticsCollector(
                                                                                                    Collections
                                                                                                            .emptySet()),
                                                                                            AlbumStatisticsCollector::addAlbumData);
                                                                            return Mono.zip(collect,
                                                                                    titleEntryId.map(Optional::of)
                                                                                            .defaultIfEmpty(
                                                                                                    Optional.empty()));
                                                                        }).next()
                                                                        .map(TupleUtils.function(
                                                                                (stats, optTitle) -> Tuples.of(stats,
                                                                                        name, version, metadata,
                                                                                        optTitle)))));

                                                return tuple5Mono.flatMap(t -> {
                                                    final AlbumStatisticsCollector stats = t.getT1();
                                                    final String name = t.getT2();
                                                    final AlbumMeta albumMeta = t.getT4();
                                                    Optional<ObjectId> titleEntryId = t.getT5();

                                                    AlbumData.AlbumDataBuilder albumDataBuilder = AlbumData.builder()
                                                            .repositoryId(albumId).name(name);
                                                    newVersion.ifPresent(albumDataBuilder::currentVersion);
                                                    Optional.ofNullable(albumMeta.getLabels())
                                                            .ifPresent(albumDataBuilder::labels);
                                                    Optional.ofNullable(albumMeta.getTitleEntry())
                                                            .ifPresent(albumDataBuilder::titleEntry);
                                                    titleEntryId.ifPresent(albumDataBuilder::titleEntryId);
                                                    stats.fill(albumDataBuilder);

                                                    return albumDataRepository.save(albumDataBuilder.build())
                                                            .timeout(Duration.ofSeconds(20));
                                                });
                                            }).doOnNext(entry -> log.info("updated: " + entry))
                                            .map(AlbumData::getRepositoryId).onErrorResume(ex -> {
                                                log.warn("Error on album " + album.getAlbumId(), ex);
                                                if (ex instanceof BulkFailureException) {
                                                    ((BulkFailureException) ex).getFailedDocuments().forEach((key1,
                                                            value1) -> log.warn("Details for " + key1 + ": " + value1));
                                                }
                                                return Mono.just(albumId);
                                            }).defaultIfEmpty(albumId);
                                })).timeout(Duration.ofHours(6))
                        /*
                         * .doFinally( signal -> { log.info("Pending, removed: " + pendingAlbums);
                         * pendingAlbums.remove(albumId); })
                         */ ;
                    }, concurrentProcessingAlbums, 100)
                    // .log("album-out")

                    .collect(Collectors.toSet())
                    .flatMap(touchedRepositories -> albumDataRepository.findAll()
                            .filter(data -> !touchedRepositories.contains(data.getRepositoryId()))
                            .doOnNext(data -> log.info("Remove " + data.getName() + "; " + data.getRepositoryId()))
                            .flatMap(entity -> albumDataEntryRepository.findByAlbumId(entity.getRepositoryId())
                                    .flatMap(albumDataEntryRepository::delete, 5)
                                    .then(albumDataRepository.delete(entity)).thenReturn(1), 10)
                            .count());

            try {
                final Long removedCount = removedRepos.block();
                if (removedCount != null && removedCount > 0)
                    log.info("Removed " + removedCount + " outdated repositories");
                break;
            } catch (Exception ex) {
                log.error("Cannot load data, retry", ex);
            }
            try {
                log.info("Start waiting");
                Thread.sleep(1000 * 10);
                log.info("End waiting");
            } catch (InterruptedException e) {
                log.info("Canceled data loader", e);
                break;
            }
        }
    }

    Mono<Boolean> fileExists(File file) {
        final Mono<Set<File>> fileOfDir = existingFilesCache.computeIfAbsent(file.getParentFile().getParentFile(),
                k -> asyncService.<Set<File>> asyncMono(() -> {
                    final long startTime = System.nanoTime();
                    if (!k.exists()) {
                        final boolean created = k.mkdirs();
                        log.info("Found empty dir at " + k + " in "
                                + (Duration.ofNanos(System.nanoTime() - startTime).toMillis()) + "ms");
                        if (!created) {
                            throw new RuntimeException("Could create directory " + k);
                        }

                        return Collections.emptySet();
                    }
                    Set<File> imageFiles = new HashSet<>();
                    final File[] subdirList = k.listFiles();
                    if (subdirList != null)
                        for (File subdir : Objects.requireNonNull(subdirList)) {
                            if (subdir.isDirectory()) {
                                final File[] filesList = subdir.listFiles();
                                if (filesList != null)
                                    for (File f : Objects.requireNonNull(filesList)) {
                                        if (f.isFile())
                                            imageFiles.add(f);
                                    }
                            }
                        }
                    log.info("Loaded " + imageFiles.size() + " at " + k + " in "
                            + (Duration.ofNanos(System.nanoTime() - startTime).toMillis()) + "ms");
                    return imageFiles;
                }).cache());
        return fileOfDir.map(files -> files.contains(file));
    }

    private Mono<Boolean> entryAlreadyProcessed(final AlbumList.FoundAlbum album,
            final GitAccess.GitFileEntry gitFileEntry, final Optional<AlbumEntryData> loadedAlbumData,
            Optional<ObjectId> xmpFileId) {

        if (loadedAlbumData.isEmpty())
            return Mono.just(false);
        final String contentType = loadedAlbumData.get().getContentType();
        final Stream<File> wantedFiles;
        if (contentType.startsWith("video")) {
            wantedFiles = Stream.concat(
                    thumbnailFilenameService.listThumbnailsOf(album.getAlbumId(), gitFileEntry.getFileId())
                            .map(ThumbnailFilenameService.FileAndScale::getFile),
                    thumbnailFilenameService.listThumbnailsOf(album.getAlbumId(), gitFileEntry.getFileId())
                            .map(ThumbnailFilenameService.FileAndScale::getVideoFile));
        } else if (contentType.startsWith("image")) {
            wantedFiles = thumbnailFilenameService.listThumbnailsOf(album.getAlbumId(), gitFileEntry.getFileId())
                    .map(ThumbnailFilenameService.FileAndScale::getFile);

        } else
            wantedFiles = Stream.empty();

        return Flux.merge(
                elasticSearchDataViewService.loadEntry(album.getAlbumId(), gitFileEntry.getFileId())
                        .map(e -> Objects.equals(e.getXmpFileId(), xmpFileId.orElse(null))).defaultIfEmpty(false),
                Flux.fromStream(wantedFiles).flatMap(this::fileExists)).all(v -> v);
    }

    @NotNull
    public Mono<Boolean> isValidEntry(final AlbumList.FoundAlbum album, final GitAccess.GitFileEntry gitFileEntry) {
        return album.getAccess().readObject(gitFileEntry.getFileId())
                .flatMap(loader -> asyncService.asyncMono(loader::getSize)).map(s -> s > 0);
    }

    @Scheduled(fixedDelay = 3600 * 1000, initialDelay = 120 * 1000)
    public void cleanupDoneJobs() {
        Instant deleteBefore = Instant.now().minus(1, ChronoUnit.DAYS);
        Flux.concat(commitJobRepository.findByCurrentPhase(CommitJob.State.DONE),
                commitJobRepository.findByCurrentPhase(CommitJob.State.FAILED))
                .filter(job -> job.getLastModified().isBefore(deleteBefore))
                .doOnNext(job -> log.info("Delete job " + job)).buffer(10).flatMap(commitJobRepository::deleteAll)
                .blockLast();
    }

    @Scheduled(fixedDelay = 7 * 1000, initialDelay = 60 * 1000)
    public void runCommits() {
        try {
            resetJobStates.timeout(Duration.ofMinutes(10))
                    .thenMany(commitJobRepository.findByCurrentPhase(CommitJob.State.READY).flatMap(this::runCommit, 1))
                    .timeout(Duration.ofHours(2)).blockLast();
        } catch (Exception ex) {
            log.warn("Cannot commit", ex);
            try {
                resetJobStates().log("reset after fail").block();
            } catch (Exception ex2) {
                log.warn("Cannot reset failed jobs", ex2);
            }
        }
    }

    private Mono<Void> resetJobStates() {
        return Flux.defer(() -> Flux.merge(commitJobRepository.findByCurrentPhase(CommitJob.State.ADD_FILES),
                commitJobRepository.findByCurrentPhase(CommitJob.State.WRITE_TREE))).map(inJob -> {
                    final CommitJob initJobState = inJob.toBuilder().currentPhase(CommitJob.State.READY).currentStep(0)
                            .totalStepCount(0).lastModified(Instant.now()).build();
                    if (initJobState.equals(inJob))
                        return Optional.<CommitJob> empty();
                    return Optional.of(initJobState);
                }).filter(Optional::isPresent).map(Optional::get).buffer(20)
                .flatMap(entities -> Flux.defer(() -> commitJobRepository.saveAll(entities))).then();
    }

    private Mono<CommitJob> runCommit(CommitJob job) {
        return executeCommit(job).buffer(Duration.ofSeconds(5)).publishOn(pollerScheduler).filter(b -> !b.isEmpty())
                .map(buffer -> buffer.get(buffer.size() - 1)).flatMap(commitJobRepository::save, 1).last();
    }

    private Flux<CommitJob> executeCommit(CommitJob job) {
        return Flux.defer(() -> {
            final int fileCount = job.getFiles().size();
            final FileImporter importer = albumList
                    .createImporter(Updater.CommitContext.builder().username(job.getUsername()).email(job.getUsermail())
                            .message("Import " + fileCount + " files").build());

            final CommitJob.CommitJobBuilder stateBuilder = job.toBuilder();
            return Flux.concat(Flux.fromIterable(job.getFiles()).index()
                    // .log("source file")
                    .flatMap(TupleUtils.function((index, file) -> {
                        final File tempUploadFile = uploadFilenameService.createTempUploadFile(file.getFileId());
                        if (!tempUploadFile.exists()) {
                            final String message = "Missing uploaded file: " + file.getFileId() + " ("
                                    + file.getFilename() + ")";
                            log.warn(message);
                            return Mono.error(new RuntimeException(message));
                        }
                        if (tempUploadFile.length() != file.getSize()) {
                            final String message = "Wrong file size on " + file.getFileId() + " (" + file.getFilename()
                                    + "): expected " + file.getSize() + ", actual: " + tempUploadFile.length();
                            log.warn(message);
                            return Mono.error(new RuntimeException(message));
                        }
                        return importer
                                .importFileIntoRepository(tempUploadFile.toPath(), file.getFilename(), job.getAlbumId())
                                .map(ignoredResult -> stateBuilder.currentPhase(CommitJob.State.ADD_FILES)
                                        .currentStep(index.intValue()).totalStepCount(fileCount)
                                        .lastModified(Instant.now()).build())
                                .doOnError(ex -> log.warn("Cannot import file " + file.getFilename(), ex));
                    }), 1).doFinally(signal -> log.info("imported all files " + signal)),
                    Flux.just(stateBuilder.currentPhase(CommitJob.State.WRITE_TREE).totalStepCount(0).currentStep(0)
                            .lastModified(Instant.now()).build()),
                    importer.commitAll()
                            // .log("commit all")
                            .map(ok -> stateBuilder.currentPhase(ok ? CommitJob.State.DONE : CommitJob.State.FAILED)
                                    .totalStepCount(0).currentStep(0).lastModified(Instant.now()).build()))
                    .onErrorResume(ex -> {
                        log.error("Cannot complete import " + job.getCommitJobId(), ex);
                        return Mono.just(
                                stateBuilder.currentPhase(CommitJob.State.FAILED).lastModified(Instant.now()).build());
                    }).publishOn(Schedulers.boundedElastic()).doFinally(signal -> importer.close().subscribe(never -> {
                    }, ex -> log.warn("Cannot close importer")));
        });
    }
}
