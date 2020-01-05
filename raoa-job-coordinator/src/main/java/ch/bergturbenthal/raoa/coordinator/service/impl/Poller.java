package ch.bergturbenthal.raoa.coordinator.service.impl;

import ch.bergturbenthal.raoa.coordinator.model.CoordinatorProperties;
import ch.bergturbenthal.raoa.coordinator.service.RemoteImageProcessor;
import ch.bergturbenthal.raoa.libs.model.elasticsearch.AlbumData;
import ch.bergturbenthal.raoa.libs.model.elasticsearch.AlbumEntryData;
import ch.bergturbenthal.raoa.libs.model.elasticsearch.KeywordCount;
import ch.bergturbenthal.raoa.libs.model.kafka.ProcessImageRequest;
import ch.bergturbenthal.raoa.libs.repository.AlbumDataEntryRepository;
import ch.bergturbenthal.raoa.libs.repository.AlbumDataRepository;
import ch.bergturbenthal.raoa.libs.repository.SyncAlbumDataEntryRepository;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.AsyncService;
import ch.bergturbenthal.raoa.libs.service.ThumbnailFilenameService;
import ch.bergturbenthal.raoa.libs.service.impl.ElasticSearchDataViewService;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.function.TupleUtils;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

@Slf4j
@Service
public class Poller {

  private final KafkaTemplate<ObjectId, ProcessImageRequest> kafkaTemplate;
  private final AlbumList albumList;
  private final ElasticSearchDataViewService elasticSearchDataViewService;
  private final ThumbnailFilenameService thumbnailFilenameService;
  private final RemoteImageProcessor remoteImageProcessor;
  private final SyncAlbumDataEntryRepository syncAlbumDataEntryRepository;
  private final AlbumDataEntryRepository albumDataEntryRepository;
  private final AlbumDataRepository albumDataRepository;
  private final AsyncService asyncService;
  private final CoordinatorProperties coordinatorProperties;

  public Poller(
      final KafkaTemplate<ObjectId, ProcessImageRequest> kafkaTemplate,
      final AlbumList albumList,
      final ElasticSearchDataViewService elasticSearchDataViewService,
      final ThumbnailFilenameService thumbnailFilenameService,
      final RemoteImageProcessor remoteImageProcessor,
      final SyncAlbumDataEntryRepository syncAlbumDataEntryRepository,
      final AlbumDataEntryRepository albumDataEntryRepository,
      final AlbumDataRepository albumDataRepository,
      final AsyncService asyncService,
      final CoordinatorProperties coordinatorProperties) {
    this.kafkaTemplate = kafkaTemplate;
    this.albumList = albumList;
    this.elasticSearchDataViewService = elasticSearchDataViewService;
    this.thumbnailFilenameService = thumbnailFilenameService;
    this.remoteImageProcessor = remoteImageProcessor;
    this.syncAlbumDataEntryRepository = syncAlbumDataEntryRepository;
    this.albumDataEntryRepository = albumDataEntryRepository;
    this.albumDataRepository = albumDataRepository;
    this.asyncService = asyncService;
    this.coordinatorProperties = coordinatorProperties;
  }

  @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 500)
  public void updateUsers() {
    try {
      elasticSearchDataViewService.updateUserData().block();
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
              .flatMap(
                  album -> {
                    return albumDataEntryRepository
                        .findByAlbumId(album.getAlbumId())
                        .collectMap(AlbumEntryData::getEntryId, k -> k)
                        .retryBackoff(10, Duration.ofSeconds(10))
                        .flatMap(
                            existingEntries -> {
                              final Mono<List<Tuple2<AlbumEntryData, Boolean>>> updatedData =
                                  album
                                      .getAccess()
                                      .listFiles(ElasticSearchDataViewService.IMAGE_FILE_FILTER)
                                      .map(
                                          gitFileEntry -> {
                                            final boolean allThumbnailsOk =
                                                thumbnailFilenameService
                                                    .listThumbnailsOf(
                                                        album.getAlbumId(),
                                                        gitFileEntry.getFileId())
                                                    .map(
                                                        ThumbnailFilenameService.FileAndScale
                                                            ::getFile)
                                                    .map(File::exists)
                                                    .collect(
                                                        () -> new AtomicBoolean(true),
                                                        (r, v) -> r.compareAndSet(true, v),
                                                        (r1, r2) ->
                                                            new AtomicBoolean(r1.get() && r2.get()))
                                                    .get();
                                            if (!allThumbnailsOk)
                                              return Tuples.of(
                                                  gitFileEntry, Optional.<AlbumEntryData>empty());
                                            else
                                              return Tuples.of(
                                                  gitFileEntry,
                                                  Optional.ofNullable(
                                                      existingEntries.get(
                                                          gitFileEntry.getFileId())));
                                          })
                                      .flatMap(
                                          entry -> {
                                            if (entry.getT2().isPresent()) {
                                              return Mono.just(
                                                  Tuples.of(entry.getT2().get(), false));
                                            }
                                            final String filename = entry.getT1().getNameString();
                                            final ProcessImageRequest data =
                                                new ProcessImageRequest(
                                                    album.getAlbumId(), filename);
                                            final ObjectId fileId = entry.getT1().getFileId();
                                            return remoteImageProcessor
                                                .processImage(fileId, data)
                                                .timeout(coordinatorProperties.getProcessTimeout())
                                                .map(ret -> Tuples.of(ret, true))
                                            // .log("proc: " + filename)
                                            ;
                                          },
                                          coordinatorProperties.getConcurrentProcessingImages())
                                      .groupBy(Tuple2::getT2)
                                      .flatMap(
                                          inFlux -> {
                                            if (inFlux.key()) {
                                              return inFlux
                                                  .map(Tuple2::getT1)
                                                  .buffer(100)
                                                  .flatMap(
                                                      updateEntries -> {
                                                        return asyncService.asyncFlux(
                                                            (Consumer<AlbumEntryData> consumer) -> {
                                                              syncAlbumDataEntryRepository
                                                                  .saveAll(updateEntries)
                                                                  .forEach(consumer);
                                                            });
                                                      },
                                                      1)
                                                  .retryBackoff(10, Duration.ofSeconds(10))
                                                  .map(e -> Tuples.of(e, true));
                                            } else return inFlux;
                                          })
                                      .collectList()
                                      .filter(list -> list.stream().anyMatch(t -> t.getT2()));
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
                                                  final Set<String> keywords = entry.getKeywords();
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
                                                albumDataRepository.save(albumDataBuilder.build());
                                            return Mono.zip(
                                                albumDataEntryRepository
                                                    .deleteById(
                                                        Flux.fromIterable(remainingEntries)
                                                            .map(id -> existingEntries.get(id))
                                                            .map(e -> e.getDocumentId()))
                                                    .map(Optional::of)
                                                    .defaultIfEmpty(Optional.empty()),
                                                save.retryBackoff(10, Duration.ofSeconds(10)));
                                          }));
                            })
                        .onErrorResume(
                            ex -> {
                              log.warn("Error on album", ex);
                              return Mono.empty();
                            });
                  },
                  coordinatorProperties.getConcurrentProcessingAlbums());
      try {
        for (Tuple2<Optional<Void>, AlbumData> entry : take.toIterable()) {
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
}
