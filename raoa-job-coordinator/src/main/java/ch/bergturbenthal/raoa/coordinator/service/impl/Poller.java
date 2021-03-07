package ch.bergturbenthal.raoa.coordinator.service.impl;

import ch.bergturbenthal.raoa.coordinator.model.CoordinatorProperties;
import ch.bergturbenthal.raoa.coordinator.service.RemoteImageProcessor;
import ch.bergturbenthal.raoa.elastic.model.AlbumData;
import ch.bergturbenthal.raoa.elastic.model.AlbumEntryData;
import ch.bergturbenthal.raoa.elastic.model.KeywordCount;
import ch.bergturbenthal.raoa.elastic.repository.AlbumDataEntryRepository;
import ch.bergturbenthal.raoa.elastic.repository.AlbumDataRepository;
import ch.bergturbenthal.raoa.elastic.repository.SyncAlbumDataEntryRepository;
import ch.bergturbenthal.raoa.elastic.service.impl.ElasticSearchDataViewService;
import ch.bergturbenthal.raoa.libs.model.kafka.ProcessImageRequest;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.AsyncService;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.ThumbnailFilenameService;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collector;
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

  @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 1000)
  public void poll() {
    log.info("scheduler started");
    while (true) {
      log.info("poll cycle started");
      final Flux<Tuple2<Optional<Void>, AlbumData>> take =
          albumList
              .listAlbums()
              // .log("album-in")
              .filterWhen(
                  album ->
                      Mono.zip(
                              album.getAccess().getCurrentVersion(),
                              albumDataRepository
                                  .findById(album.getAlbumId())
                                  .map(AlbumData::getCurrentVersion))
                          .map(t -> t.getT1().equals(t.getT2()))
                          .defaultIfEmpty(false)
                          .onErrorResume(
                              ex -> {
                                log.warn("Cannot check album " + album.getAccess(), ex);
                                return Mono.just(false);
                              })
                          .map(b -> !b)
                          .timeout(Duration.ofSeconds(60))
                          .retryBackoff(10, Duration.ofSeconds(10)),
                  10)
              .flatMap(
                  album -> {
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
                                          .listFiles(ElasticSearchDataViewService.IMAGE_FILE_FILTER)
                                          .filterWhen(
                                              gitFileEntry -> isValidEntry(album, gitFileEntry))
                                          .flatMap(
                                              gitFileEntry ->
                                                  entryAlreadyProcessed(album, gitFileEntry)
                                                      .map(
                                                          exists ->
                                                              Tuples.of(gitFileEntry, exists)))
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
                                                              : Optional.<AlbumEntryData>empty()))
                                          .flatMap(
                                              entry -> {
                                                if (entry.getT2().isPresent()) {
                                                  return Mono.just(
                                                      Tuples.of(entry.getT2().get(), false));
                                                }
                                                final String filename =
                                                    entry.getT1().getNameString();
                                                final ProcessImageRequest data =
                                                    new ProcessImageRequest(
                                                        album.getAlbumId(), filename);
                                                final ObjectId fileId = entry.getT1().getFileId();
                                                final long startTime = System.nanoTime();
                                                /*log.info(
                                                "start processing ["
                                                    + data.getAlbumId()
                                                    + "] "
                                                    + filename);*/
                                                return remoteImageProcessor
                                                    .processImage(fileId, data)
                                                    // .log(filename)
                                                    .timeout(
                                                        coordinatorProperties.getProcessTimeout())
                                                    .retryWhen(
                                                        Retry.backoff(10, Duration.ofSeconds(5))
                                                            .maxBackoff(Duration.ofMinutes(2))
                                                            .jitter(0.5d)
                                                            .scheduler(Schedulers.parallel())
                                                            .transientErrors(false))
                                                    .map(ret -> Tuples.of(ret, true))
                                                    .doFinally(
                                                        signal ->
                                                            log.info(
                                                                "processed ["
                                                                    + data.getAlbumId()
                                                                    + "] "
                                                                    + filename
                                                                    + " in "
                                                                    + Duration.ofNanos(
                                                                            System.nanoTime()
                                                                                - startTime)
                                                                        .getSeconds()
                                                                    + "s with "
                                                                    + signal))

                                                // .log("proc: " + filename)
                                                ;
                                              },
                                              coordinatorProperties.getConcurrentProcessingImages(),
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
                                              (GroupedFlux<Boolean, Tuple2<AlbumEntryData, Boolean>>
                                                      inFlux) -> {
                                                final Boolean key = inFlux.key();
                                                if (key != null && key) {
                                                  return inFlux
                                                      // .log("store")
                                                      .map(Tuple2::getT1)
                                                      .bufferTimeout(100, Duration.ofSeconds(30))
                                                      .flatMap(
                                                          updateEntries -> {
                                                            log.info(
                                                                "Prepare store of "
                                                                    + updateEntries.size());
                                                            return albumDataEntryRepository.saveAll(
                                                                updateEntries)
                                                            // .log("batch store")
                                                            ;

                                                            /*return asyncService
                                                            .asyncFlux(
                                                                (Consumer<AlbumEntryData>
                                                                        consumer) -> {
                                                                  try {
                                                                    log.info(
                                                                        "Start store of "
                                                                            + updateEntries.size());
                                                                    final Iterable<AlbumEntryData>
                                                                        albumEntryData =
                                                                            meterRegistry
                                                                                .timer(
                                                                                    "raoa.poller.storage.flush")
                                                                                .recordCallable(
                                                                                    () ->
                                                                                        syncAlbumDataEntryRepository
                                                                                            .saveAll(
                                                                                                updateEntries));
                                                                    meterRegistry
                                                                        .counter(
                                                                            "raoa.poller.storage.entries")
                                                                        .increment(
                                                                            updateEntries.size());
                                                                    log.info(
                                                                        "Done store of "
                                                                            + updateEntries.size());
                                                                    albumEntryData.forEach(
                                                                        consumer);
                                                                    log.info(
                                                                        "Done publish of "
                                                                            + updateEntries.size());
                                                                  } catch (Exception ex) {
                                                                    log.warn("Error storing", ex);
                                                                    throw ex;
                                                                  }
                                                                })
                                                            .retryWhen(
                                                                Retry.backoff(
                                                                    10, Duration.ofSeconds(10)));*/
                                                          },
                                                          2)
                                                      // .publishOn(Schedulers.elastic())
                                                      .map(e -> Tuples.of(e, true))
                                                  // .log("processed")
                                                  ;
                                                } else
                                                  return inFlux // .log("bypass")
                                                  ;
                                              })
                                          // .log("joined")
                                          .collectList()
                                      // .filter(list -> list.stream().anyMatch(Tuple2::getT2))
                                      ;
                                  final Mono<String> nameMono = album.getAccess().getName();
                                  final Mono<ObjectId> versionMono =
                                      album.getAccess().getCurrentVersion();
                                  return Mono.zip(updatedData, nameMono, versionMono)
                                      .flatMap(
                                          TupleUtils.function(
                                              (data, name, currentVersion) -> {
                                                final LongSummaryStatistics timeSummary =
                                                    new LongSummaryStatistics();
                                                final LongAdder entryCount = new LongAdder();

                                                final Set<ObjectId> remainingEntries =
                                                    new HashSet<>(existingEntries.keySet());
                                                Map<String, AtomicInteger> keywordCounts =
                                                    Collections.synchronizedMap(new HashMap<>());

                                                data.forEach(
                                                    t -> {
                                                      final AlbumEntryData entry = t.getT1();
                                                      remainingEntries.remove(entry.getEntryId());
                                                      if (entry.getCreateTime() != null) {
                                                        timeSummary.accept(
                                                            entry.getCreateTime().getEpochSecond());
                                                      }
                                                      entryCount.increment();
                                                      final Set<String> keywords =
                                                          entry.getKeywords();
                                                      if (keywords != null) {
                                                        for (String keyword : keywords)
                                                          keywordCounts
                                                              .computeIfAbsent(
                                                                  keyword, k -> new AtomicInteger())
                                                              .incrementAndGet();
                                                      }
                                                    });
                                                AlbumData.AlbumDataBuilder albumDataBuilder =
                                                    AlbumData.builder()
                                                        .repositoryId(album.getAlbumId())
                                                        .currentVersion(currentVersion)
                                                        .name(name);
                                                if (timeSummary.getCount() > 0)
                                                  albumDataBuilder.createTime(
                                                      Instant.ofEpochSecond(
                                                          (long) timeSummary.getAverage()));
                                                albumDataBuilder.entryCount(entryCount.intValue());
                                                albumDataBuilder.keywordCount(
                                                    keywordCounts.entrySet().stream()
                                                        .map(
                                                            e ->
                                                                KeywordCount.builder()
                                                                    .keyword(e.getKey())
                                                                    .entryCount(e.getValue().get())
                                                                    .build())
                                                        .collect(Collectors.toList()));
                                                final Mono<AlbumData> save =
                                                    albumDataRepository
                                                        .save(albumDataBuilder.build())
                                                        .timeout(Duration.ofSeconds(20));
                                                return Mono.zip(
                                                    albumDataEntryRepository
                                                        .deleteById(
                                                            Flux.fromIterable(remainingEntries)
                                                                .map(existingEntries::get)
                                                                .map(AlbumEntryData::getDocumentId))
                                                        .map(Optional::of)
                                                        .defaultIfEmpty(Optional.empty())
                                                        .timeout(Duration.ofSeconds(30)),
                                                    save.retryBackoff(10, Duration.ofSeconds(10)));
                                              }));
                                });
                    return tuple2Mono
                        // .timeout(Duration.ofMinutes(30))
                        .log("process " + album.getAlbumId())
                        .onErrorResume(
                            ex -> {
                              log.warn("Error on album", ex);
                              if (ex instanceof BulkFailureException) {
                                ((BulkFailureException) ex)
                                    .getFailedDocuments()
                                    .forEach((key, value) -> log.warn(value));
                              }
                              return Mono.empty();
                            });
                  },
                  coordinatorProperties.getConcurrentProcessingAlbums());
      try {
        for (Tuple2<Optional<Void>, AlbumData> entry :
            take.timeout(Duration.ofHours(5)).toIterable()) {
          log.info("updated: " + entry.getT2().getName());
        }
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
      final AlbumList.FoundAlbum album, final GitAccess.GitFileEntry gitFileEntry) {

    return Flux.fromStream(
            thumbnailFilenameService
                .listThumbnailsOf(album.getAlbumId(), gitFileEntry.getFileId())
                .map(ThumbnailFilenameService.FileAndScale::getFile))
        .flatMap(this::fileExists)
        .collect(
            Collector.of(
                () -> new AtomicBoolean(true),
                (r, v) -> r.compareAndSet(true, v),
                (r1, r2) -> new AtomicBoolean(r1.get() && r2.get())))
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
