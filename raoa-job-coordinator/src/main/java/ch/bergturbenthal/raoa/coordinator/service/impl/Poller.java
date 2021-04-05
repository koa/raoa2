package ch.bergturbenthal.raoa.coordinator.service.impl;

import ch.bergturbenthal.raoa.coordinator.model.CoordinatorProperties;
import ch.bergturbenthal.raoa.coordinator.service.RemoteImageProcessor;
import ch.bergturbenthal.raoa.elastic.model.AlbumData;
import ch.bergturbenthal.raoa.elastic.model.AlbumEntryData;
import ch.bergturbenthal.raoa.elastic.repository.AlbumDataEntryRepository;
import ch.bergturbenthal.raoa.elastic.repository.AlbumDataRepository;
import ch.bergturbenthal.raoa.elastic.repository.SyncAlbumDataEntryRepository;
import ch.bergturbenthal.raoa.elastic.service.impl.AlbumStatisticsCollector;
import ch.bergturbenthal.raoa.elastic.service.impl.ElasticSearchDataViewService;
import ch.bergturbenthal.raoa.libs.model.ProcessImageRequest;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.AsyncService;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.ThumbnailFilenameService;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.File;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.map.LRUMap;
import org.eclipse.jgit.lib.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.elasticsearch.BulkFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.GroupedFlux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.function.TupleUtils;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import reactor.util.retry.Retry;

@Slf4j
@Service
public class Poller {

  private final AlbumList albumList;
  private final ElasticSearchDataViewService elasticSearchDataViewService;
  private final ThumbnailFilenameService thumbnailFilenameService;
  private final RemoteImageProcessor remoteImageProcessor;
  private final SyncAlbumDataEntryRepository syncAlbumDataEntryRepository;
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
      final RemoteImageProcessor remoteImageProcessor,
      final SyncAlbumDataEntryRepository syncAlbumDataEntryRepository,
      final AlbumDataEntryRepository albumDataEntryRepository,
      final AlbumDataRepository albumDataRepository,
      final AsyncService asyncService,
      final CoordinatorProperties coordinatorProperties,
      final MeterRegistry meterRegistry) {
    this.albumList = albumList;
    this.elasticSearchDataViewService = elasticSearchDataViewService;
    this.thumbnailFilenameService = thumbnailFilenameService;
    this.remoteImageProcessor = remoteImageProcessor;
    this.syncAlbumDataEntryRepository = syncAlbumDataEntryRepository;
    this.albumDataEntryRepository = albumDataEntryRepository;
    this.albumDataRepository = albumDataRepository;
    this.asyncService = asyncService;
    this.coordinatorProperties = coordinatorProperties;
    this.meterRegistry = meterRegistry;
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
                    final Mono<Tuple2<Optional<Void>, AlbumData>> tuple2Mono =
                        albumDataEntryRepository
                            .findByAlbumId(album.getAlbumId())
                            // .log("entry before")
                            .collectMap(AlbumEntryData::getEntryId)
                            .retryWhen(Retry.backoff(10, Duration.ofSeconds(10)))
                            .flatMap(
                                existingEntries -> {
                                  final Mono<List<Tuple2<AlbumEntryData, Boolean>>> updatedData =
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
                                              xmpFiles ->
                                                  album
                                                      .getAccess()
                                                      .listFiles(
                                                          ElasticSearchDataViewService
                                                              .IMAGE_FILE_FILTER)
                                                      .filterWhen(
                                                          gitFileEntry ->
                                                              isValidEntry(album, gitFileEntry))
                                                      .flatMap(
                                                          gitFileEntry ->
                                                              entryAlreadyProcessed(
                                                                      album,
                                                                      gitFileEntry,
                                                                      Optional.ofNullable(
                                                                          xmpFiles.get(
                                                                              gitFileEntry
                                                                                  .getNameString())))
                                                                  .map(
                                                                      exists ->
                                                                          Tuples.of(
                                                                              gitFileEntry,
                                                                              exists)))
                                                      .map(
                                                          (Tuple2<GitAccess.GitFileEntry, Boolean>
                                                                  gitFileEntry) ->
                                                              gitFileEntry.mapT2(
                                                                  b ->
                                                                      b
                                                                          ? Optional.ofNullable(
                                                                              existingEntries.get(
                                                                                  gitFileEntry
                                                                                      .getT1()
                                                                                      .getFileId()))
                                                                          : Optional
                                                                              .<AlbumEntryData>
                                                                                  empty()))
                                                      .flatMap(
                                                          entry1 -> {
                                                            if (entry1.getT2().isPresent()) {
                                                              return Mono.just(
                                                                  Tuples.of(
                                                                      entry1.getT2().get(), false));
                                                            }
                                                            final String filename =
                                                                entry1.getT1().getNameString();
                                                            final ProcessImageRequest data1 =
                                                                new ProcessImageRequest(
                                                                    album.getAlbumId(), filename);
                                                            final ObjectId fileId =
                                                                entry1.getT1().getFileId();
                                                            final long startTime =
                                                                System.nanoTime();
                                                            /*log.info(
                                                            "start processing ["
                                                                + data.getAlbumId()
                                                                + "] "
                                                                + filename);*/
                                                            return remoteImageProcessor
                                                                .processImage(fileId, data1)
                                                                // .log(filename)
                                                                .timeout(
                                                                    coordinatorProperties
                                                                        .getProcessTimeout())
                                                                .retryWhen(
                                                                    Retry.backoff(
                                                                            10,
                                                                            Duration.ofSeconds(5))
                                                                        .maxBackoff(
                                                                            Duration.ofMinutes(2))
                                                                        .jitter(0.5d)
                                                                        .scheduler(
                                                                            Schedulers.parallel())
                                                                        .transientErrors(false))
                                                                .map(ret -> Tuples.of(ret, true))
                                                                .doFinally(
                                                                    signal ->
                                                                        log.info(
                                                                            "processed ["
                                                                                + data1.getAlbumId()
                                                                                + "] "
                                                                                + filename
                                                                                + " in "
                                                                                + Duration.ofNanos(
                                                                                        System
                                                                                                .nanoTime()
                                                                                            - startTime)
                                                                                    .getSeconds()
                                                                                + "s with "
                                                                                + signal))

                                                            // .log("proc: " + filename)
                                                            ;
                                                          },
                                                          coordinatorProperties
                                                              .getConcurrentProcessingImages(),
                                                          50)
                                                      // .log("entry of " + album)
                                                      // .log("in")
                                                      /*.doOnNext(
                                                      data -> {
                                                        if (data.getT2())
                                                          log.info("Processing " + data.getT1());
                                                        else log.info("Bypassing " + data.getT1());
                                                      })*/
                                                      .groupBy(Tuple2::getT2)
                                                      .flatMap(
                                                          (GroupedFlux<
                                                                      Boolean,
                                                                      Tuple2<
                                                                          AlbumEntryData, Boolean>>
                                                                  inFlux) -> {
                                                            if (inFlux.key()) {
                                                              return inFlux
                                                                  // .log("store")
                                                                  .map(Tuple2::getT1)
                                                                  .bufferTimeout(
                                                                      100, Duration.ofSeconds(30))
                                                                  .flatMap(
                                                                      updateEntries -> {
                                                                        log.info(
                                                                            "Prepare store of "
                                                                                + updateEntries
                                                                                    .size());
                                                                        return albumDataEntryRepository
                                                                            .saveAll(updateEntries)
                                                                        // .log("batch store")
                                                                        ;
                                                                      },
                                                                      2)
                                                                  // .publishOn(Schedulers.elastic())
                                                                  .map(e1 -> Tuples.of(e1, true))
                                                              // .log("processed")
                                                              ;
                                                            } else
                                                              return inFlux // .log("bypass")
                                                              ;
                                                          })
                                                      // .log("joined")
                                                      .collectList());
                                  final Mono<String> nameMono = album.getAccess().getName();
                                  final Mono<ObjectId> versionMono =
                                      album.getAccess().getCurrentVersion();
                                  return Mono.zip(
                                          updatedData,
                                          nameMono,
                                          versionMono,
                                          album.getAccess().getMetadata())
                                      .flatMap(
                                          TupleUtils.function(
                                              (data1, name, currentVersion, metadata) -> {
                                                final AlbumStatisticsCollector collector =
                                                    new AlbumStatisticsCollector(
                                                        existingEntries.keySet());
                                                data1.forEach(
                                                    t -> collector.addAlbumData(t.getT1()));

                                                AlbumData.AlbumDataBuilder albumDataBuilder =
                                                    AlbumData.builder()
                                                        .repositoryId(album.getAlbumId())
                                                        .currentVersion(currentVersion)
                                                        .name(name);
                                                Optional.ofNullable(metadata.getLabels())
                                                    .ifPresent(albumDataBuilder::labels);
                                                collector.fill(albumDataBuilder);

                                                final Mono<AlbumData> save =
                                                    albumDataRepository
                                                        .save(albumDataBuilder.build())
                                                        .timeout(Duration.ofSeconds(20));
                                                return Mono.zip(
                                                    albumDataEntryRepository
                                                        .deleteById(
                                                            Flux.fromIterable(
                                                                    collector.getRemainingEntries())
                                                                .map(existingEntries::get)
                                                                .map(AlbumEntryData::getDocumentId))
                                                        .map(Optional::of)
                                                        .defaultIfEmpty(Optional.empty())
                                                        .timeout(Duration.ofSeconds(30)),
                                                    save.retryWhen(
                                                        Retry.backoff(10, Duration.ofSeconds(10))));
                                              }));
                                });
                    return tuple2Mono
                        .map(Tuple2::getT2)
                        // .timeout(Duration.ofMinutes(30))
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
              .timeout(Duration.ofHours(1))
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
                          .flatMap(entity -> albumDataRepository.delete(entity).thenReturn(1))
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
                            log.info(
                                "Found empty dir at "
                                    + k
                                    + " in "
                                    + (Duration.ofNanos(System.nanoTime() - startTime).toMillis())
                                    + "ms");

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

  public Mono<Boolean> entryAlreadyProcessed(
      final AlbumList.FoundAlbum album,
      final GitAccess.GitFileEntry gitFileEntry,
      Optional<ObjectId> xmpFileId) {

    return Flux.merge(
            elasticSearchDataViewService
                .loadEntry(album.getAlbumId(), gitFileEntry.getFileId())
                .map(e -> Objects.equals(e.getXmpFileId(), xmpFileId.orElse(null))),
            Flux.fromStream(
                    thumbnailFilenameService
                        .listThumbnailsOf(album.getAlbumId(), gitFileEntry.getFileId())
                        .map(ThumbnailFilenameService.FileAndScale::getFile))
                .flatMap(this::fileExists))
        .collect(() -> new AtomicBoolean(true), (r, v) -> r.compareAndSet(true, v))
        .map(AtomicBoolean::get);
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
