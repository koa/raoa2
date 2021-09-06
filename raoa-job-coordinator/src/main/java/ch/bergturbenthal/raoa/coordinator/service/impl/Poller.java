package ch.bergturbenthal.raoa.coordinator.service.impl;

import ch.bergturbenthal.raoa.coordinator.model.CoordinatorProperties;
import ch.bergturbenthal.raoa.coordinator.service.RemoteMediaProcessor;
import ch.bergturbenthal.raoa.elastic.model.AlbumData;
import ch.bergturbenthal.raoa.elastic.model.AlbumEntryData;
import ch.bergturbenthal.raoa.elastic.repository.AlbumDataEntryRepository;
import ch.bergturbenthal.raoa.elastic.repository.AlbumDataRepository;
import ch.bergturbenthal.raoa.elastic.service.impl.AlbumStatisticsCollector;
import ch.bergturbenthal.raoa.elastic.service.impl.ElasticSearchDataViewService;
import ch.bergturbenthal.raoa.libs.model.AlbumMeta;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.AsyncService;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.ThumbnailFilenameService;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.File;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.map.LRUMap;
import org.eclipse.jgit.lib.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.elasticsearch.BulkFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import reactor.util.retry.Retry;

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
  private Map<File, Mono<Set<File>>> existingFilesCache =
      Collections.synchronizedMap(new LRUMap<>(50));

  public Poller(
      final AlbumList albumList,
      final ElasticSearchDataViewService elasticSearchDataViewService,
      final ThumbnailFilenameService thumbnailFilenameService,
      final RemoteMediaProcessor remoteMediaProcessor,
      final AlbumDataEntryRepository albumDataEntryRepository,
      final AlbumDataRepository albumDataRepository,
      final AsyncService asyncService,
      final CoordinatorProperties coordinatorProperties,
      final MeterRegistry meterRegistry) {
    this.albumList = albumList;
    this.elasticSearchDataViewService = elasticSearchDataViewService;
    this.thumbnailFilenameService = thumbnailFilenameService;
    this.remoteMediaProcessor = remoteMediaProcessor;
    this.albumDataEntryRepository = albumDataEntryRepository;
    this.albumDataRepository = albumDataRepository;
    this.asyncService = asyncService;
    this.coordinatorProperties = coordinatorProperties;
    this.meterRegistry = meterRegistry;
  }

  private static Integer batchSizeByFilename(final String nameString) {
    final String lowerFilename = nameString.toLowerCase(Locale.ROOT);
    if (lowerFilename.endsWith(".jpg")) return 1000;
    if (lowerFilename.endsWith(".nef")) return 100;
    if (lowerFilename.endsWith(".mp4")) return 1;
    return 0;
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
      final Mono<Long> removedRepos =
          albumList
              .listAlbums()
              // .log("album-in")
              .flatMap(
                  album ->
                      Mono.zip(
                              album.getAccess().getCurrentVersion(),
                              albumDataRepository
                                  .findById(album.getAlbumId())
                                  .map(AlbumData::getCurrentVersion))
                          .map(t -> t.getT1().equals(t.getT2()))
                          .defaultIfEmpty(false)
                          .onErrorResume(
                              ex1 -> {
                                log.warn("Cannot check album " + album.getAccess(), ex1);
                                return Mono.just(false);
                              })
                          .map(b -> !b)
                          .timeout(Duration.ofSeconds(60))
                          .retryWhen(Retry.backoff(10, Duration.ofSeconds(10)))
                          .map(touched -> Tuples.of(album, touched)),
                  10)
              .flatMap(
                  albumData -> {
                    boolean touched = albumData.getT2();
                    if (!touched) return Mono.just(albumData.getT1().getAlbumId());
                    final AlbumList.FoundAlbum album = albumData.getT1();
                    log.info("Start " + album);
                    final UUID albumId = album.getAlbumId();
                    final Mono<AlbumData> tuple2Mono =
                        albumDataEntryRepository
                            .findByAlbumId(albumId)
                            // .log("entry before")
                            .collectMap(AlbumEntryData::getEntryId)
                            .retryWhen(Retry.backoff(10, Duration.ofSeconds(10)))
                            .flatMap(
                                existingEntries ->
                                    album
                                        .getAccess()
                                        .listFiles(ElasticSearchDataViewService.XMP_FILE_FILTER)
                                        .collectMap(
                                            fe -> {
                                              final String filename = fe.getNameString();
                                              return filename.substring(0, filename.length() - 4);
                                            },
                                            GitAccess.GitFileEntry::getFileId)
                                        .flatMap(
                                            xmpFiles -> {
                                              final Flux<GitAccess.GitFileEntry>
                                                  allCurrentMediaFiles =
                                                      album
                                                          .getAccess()
                                                          .listFiles(
                                                              ElasticSearchDataViewService
                                                                  .MEDIA_FILE_FILTER)
                                                          .filterWhen(
                                                              gitFileEntry ->
                                                                  isValidEntry(album, gitFileEntry),
                                                              5);

                                              return allCurrentMediaFiles
                                                  .map(GitAccess.GitFileEntry::getFileId)
                                                  .collect(Collectors.toUnmodifiableSet())
                                                  .flatMap(
                                                      currentValidMediaFiles ->
                                                          albumDataEntryRepository.deleteAll(
                                                              Flux.fromIterable(
                                                                      existingEntries.entrySet())
                                                                  .filter(
                                                                      storedEntry ->
                                                                          !currentValidMediaFiles
                                                                              .contains(
                                                                                  storedEntry
                                                                                      .getKey()))
                                                                  .map(Map.Entry::getValue)))
                                                  .then(
                                                      allCurrentMediaFiles
                                                          .map(
                                                              gitFileEntry1 ->
                                                                  Tuples.of(
                                                                      gitFileEntry1,
                                                                      Optional.ofNullable(
                                                                          existingEntries.get(
                                                                              gitFileEntry1
                                                                                  .getFileId()))))
                                                          .filterWhen(
                                                              gitFileEntry1 ->
                                                                  entryAlreadyProcessed(
                                                                          album,
                                                                          gitFileEntry1.getT1(),
                                                                          gitFileEntry1.getT2(),
                                                                          Optional.ofNullable(
                                                                              xmpFiles.get(
                                                                                  gitFileEntry1
                                                                                      .getT1()
                                                                                      .getNameString())))
                                                                      .map(b -> !b))
                                                          .map(Tuple2::getT1)
                                                          .map(
                                                              GitAccess.GitFileEntry::getNameString)
                                                          .map(
                                                              filename ->
                                                                  Tuples.of(
                                                                      filename,
                                                                      batchSizeByFilename(
                                                                          filename)))
                                                          .filter(t -> t.getT2() > 0)
                                                          .groupBy(Tuple2::getT2)
                                                          .flatMap(
                                                              group ->
                                                                  group
                                                                      .map(Tuple2::getT1)
                                                                      .buffer(group.key()))
                                                          .flatMap(
                                                              batch ->
                                                                  remoteMediaProcessor
                                                                      .processFiles(albumId, batch)
                                                                      .doOnNext(
                                                                          ok ->
                                                                              log.info(
                                                                                  "Processed "
                                                                                      + batch.size()
                                                                                      + " files ")),
                                                              coordinatorProperties
                                                                  .getConcurrentProcessingImages())
                                                          .all(ok -> ok))
                                                  .filter(ok -> ok)
                                                  .flatMap(
                                                      allFilesProcessed -> {
                                                        final Mono<String> nameMono =
                                                            album.getAccess().getName();
                                                        final Mono<ObjectId> versionMono =
                                                            album.getAccess().getCurrentVersion();
                                                        final Mono<AlbumMeta> metadata =
                                                            album.getAccess().getMetadata();

                                                        return Mono.zip(
                                                                albumDataEntryRepository
                                                                    .findByAlbumId(albumId)
                                                                    .collect(
                                                                        () ->
                                                                            new AlbumStatisticsCollector(
                                                                                Collections
                                                                                    .emptySet()),
                                                                        AlbumStatisticsCollector
                                                                            ::addAlbumData),
                                                                nameMono,
                                                                versionMono,
                                                                metadata)
                                                            .flatMap(
                                                                t -> {
                                                                  final AlbumStatisticsCollector
                                                                      stats = t.getT1();
                                                                  final String name = t.getT2();
                                                                  final ObjectId version =
                                                                      t.getT3();
                                                                  final AlbumMeta albumMeta =
                                                                      t.getT4();

                                                                  AlbumData.AlbumDataBuilder
                                                                      albumDataBuilder =
                                                                          AlbumData.builder()
                                                                              .repositoryId(albumId)
                                                                              .currentVersion(
                                                                                  version)
                                                                              .name(name);
                                                                  Optional.ofNullable(
                                                                          albumMeta.getLabels())
                                                                      .ifPresent(
                                                                          albumDataBuilder::labels);
                                                                  stats.fill(albumDataBuilder);

                                                                  return albumDataRepository
                                                                      .save(
                                                                          albumDataBuilder.build())
                                                                      .timeout(
                                                                          Duration.ofSeconds(20));
                                                                });
                                                      });
                                            }));
                    return tuple2Mono
                        .timeout(Duration.ofHours(6))
                        // .log("process " + album.getAlbumId())
                        .doOnNext(entry -> log.info("updated: " + entry.getName()))
                        .map(AlbumData::getRepositoryId)
                        .onErrorResume(
                            ex1 -> {
                              log.warn("Error on album", ex1);
                              if (ex1 instanceof BulkFailureException) {
                                ((BulkFailureException) ex1)
                                    .getFailedDocuments()
                                    .forEach((key, value) -> log.warn(value));
                              }
                              return Mono.empty();
                            });
                  },
                  coordinatorProperties.getConcurrentProcessingAlbums())
              .collect(Collectors.toSet())
              .flatMap(
                  touchedRepositories ->
                      albumDataRepository
                          .findAll()
                          .filter(data -> !touchedRepositories.contains(data.getRepositoryId()))
                          .doOnNext(
                              data ->
                                  log.info(
                                      "Remove " + data.getName() + "; " + data.getRepositoryId()))
                          .flatMap(
                              entity ->
                                  albumDataEntryRepository
                                      .findByAlbumId(entity.getRepositoryId())
                                      .flatMap(albumDataEntryRepository::delete, 5)
                                      .then(albumDataRepository.delete(entity))
                                      .thenReturn(1),
                              10)
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
    final Mono<Set<File>> fileOfDir =
        existingFilesCache.computeIfAbsent(
            file.getParentFile().getParentFile(),
            k ->
                asyncService
                    .<Set<File>>asyncMono(
                        () -> {
                          final long startTime = System.nanoTime();
                          if (!k.exists()) {
                            final boolean created = k.mkdirs();
                            log.info(
                                "Found empty dir at "
                                    + k
                                    + " in "
                                    + (Duration.ofNanos(System.nanoTime() - startTime).toMillis())
                                    + "ms");
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
                                    if (f.isFile()) imageFiles.add(f);
                                  }
                              }
                            }
                          log.info(
                              "Loaded "
                                  + imageFiles.size()
                                  + " at "
                                  + k
                                  + " in "
                                  + (Duration.ofNanos(System.nanoTime() - startTime).toMillis())
                                  + "ms");
                          return imageFiles;
                        })
                    .cache());
    return fileOfDir.map(files -> files.contains(file));
  }

  private Mono<Boolean> entryAlreadyProcessed(
      final AlbumList.FoundAlbum album,
      final GitAccess.GitFileEntry gitFileEntry,
      final Optional<AlbumEntryData> loadedAlbumData,
      Optional<ObjectId> xmpFileId) {

    if (loadedAlbumData.isEmpty()) return Mono.just(false);
    final String contentType = loadedAlbumData.get().getContentType();
    final Stream<File> wantedFiles;
    if (contentType.startsWith("video")) {
      wantedFiles =
          Stream.concat(
              thumbnailFilenameService
                  .listThumbnailsOf(album.getAlbumId(), gitFileEntry.getFileId())
                  .map(ThumbnailFilenameService.FileAndScale::getFile),
              thumbnailFilenameService
                  .listThumbnailsOf(album.getAlbumId(), gitFileEntry.getFileId())
                  .map(ThumbnailFilenameService.FileAndScale::getVideoFile));
    } else if (contentType.startsWith("image")) {
      wantedFiles =
          thumbnailFilenameService
              .listThumbnailsOf(album.getAlbumId(), gitFileEntry.getFileId())
              .map(ThumbnailFilenameService.FileAndScale::getFile);

    } else wantedFiles = Stream.empty();

    return Flux.merge(
            elasticSearchDataViewService
                .loadEntry(album.getAlbumId(), gitFileEntry.getFileId())
                .map(e -> Objects.equals(e.getXmpFileId(), xmpFileId.orElse(null)))
                .defaultIfEmpty(false),
            Flux.fromStream(wantedFiles).flatMap(this::fileExists))
        .all(v -> v);
  }

  @NotNull
  public Mono<Boolean> isValidEntry(
      final AlbumList.FoundAlbum album, final GitAccess.GitFileEntry gitFileEntry) {
    return album
        .getAccess()
        .readObject(gitFileEntry.getFileId())
        .flatMap(loader -> asyncService.asyncMono(loader::getSize))
        .map(s -> s > 0);
  }
}
