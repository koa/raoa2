package ch.bergturbenthal.raoa.viewer.service.impl;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.viewer.model.elasticsearch.AlbumData;
import ch.bergturbenthal.raoa.viewer.model.elasticsearch.AlbumEntryData;
import ch.bergturbenthal.raoa.viewer.repository.AlbumDataEntryRepository;
import ch.bergturbenthal.raoa.viewer.repository.AlbumDataRepository;
import ch.bergturbenthal.raoa.viewer.repository.SyncAlbumDataEntryRepository;
import ch.bergturbenthal.raoa.viewer.service.ImageDataService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.*;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.treewalk.filter.OrTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.function.TupleUtils;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

@Slf4j
@Service
public class ElasticSearchImageDataService implements ImageDataService {
  public static final TreeFilter IMAGE_FILE_FILTER =
      OrTreeFilter.create(
          new TreeFilter[] {
            PathSuffixFilter.create(".jpg"),
            PathSuffixFilter.create(".jpeg"),
            PathSuffixFilter.create(".JPG"),
            PathSuffixFilter.create(".JPEG"),
            PathSuffixFilter.create(".mp4"),
            PathSuffixFilter.create(".MP4"),
            PathSuffixFilter.create(".mkv")
          });
  private final AlbumDataRepository albumDataRepository;
  private final AlbumDataEntryRepository albumDataEntryRepository;
  private final AlbumList albumList;
  private final SyncAlbumDataEntryRepository syncAlbumDataEntryRepository;

  public ElasticSearchImageDataService(
      final AlbumDataRepository albumDataRepository,
      final AlbumDataEntryRepository albumDataEntryRepository,
      final AlbumList albumList,
      final SyncAlbumDataEntryRepository syncAlbumDataEntryRepository) {
    this.albumDataRepository = albumDataRepository;
    this.albumDataEntryRepository = albumDataEntryRepository;
    this.albumList = albumList;
    this.syncAlbumDataEntryRepository = syncAlbumDataEntryRepository;
    log.info("Initialized");
  }

  private static Optional<Integer> extractTargetWidth(final Metadata m) {
    if (Optional.ofNullable(m.get(TIFF.ORIENTATION)).map(Integer::valueOf).orElse(0) <= 4) {
      return Optional.ofNullable(m.getInt(TIFF.IMAGE_WIDTH));
    } else {
      return Optional.ofNullable(m.getInt(TIFF.IMAGE_LENGTH));
    }
  }

  private static Optional<Integer> extractTargetHeight(final Metadata m) {
    if (Optional.ofNullable(m.get(TIFF.ORIENTATION)).map(Integer::valueOf).orElse(0) <= 4) {
      return Optional.ofNullable(m.getInt(TIFF.IMAGE_LENGTH));
    } else {
      return Optional.ofNullable(m.getInt(TIFF.IMAGE_WIDTH));
    }
  }

  private static Optional<Integer> extractInteger(final Metadata m, final Property property) {
    return Optional.ofNullable(m.getInt(property));
  }

  private static Optional<String> extractString(final Metadata m, final Property property) {
    return Optional.ofNullable(m.get(property));
  }

  private static Optional<Instant> extractInstant(final Metadata m, final Property property) {
    return Optional.ofNullable(m.getDate(property)).map(Date::toInstant);
  }

  private static Optional<Double> extractDouble(final Metadata m, final Property property) {
    return Optional.ofNullable(m.get(property)).map(Double::valueOf);
  }

  private static AlbumEntryData createAlbumEntry(
      final GitAccess.GitFileEntry gitFileEntry, final Metadata metadata, final UUID albumId) {
    final AlbumEntryData.AlbumEntryDataBuilder albumEntryDataBuilder =
        AlbumEntryData.builder()
            .filename(gitFileEntry.getNameString())
            .entryId(gitFileEntry.getFileId())
            .albumId(albumId);
    extractInstant(metadata, TikaCoreProperties.CREATED)
        .ifPresent(albumEntryDataBuilder::createTime);
    extractTargetWidth(metadata).ifPresent(albumEntryDataBuilder::targetWidth);
    extractTargetHeight(metadata).ifPresent(albumEntryDataBuilder::targetHeight);
    extractInteger(metadata, TIFF.IMAGE_WIDTH).ifPresent(albumEntryDataBuilder::width);
    extractInteger(metadata, TIFF.IMAGE_LENGTH).ifPresent(albumEntryDataBuilder::height);
    extractString(metadata, TIFF.EQUIPMENT_MODEL).ifPresent(albumEntryDataBuilder::cameraModel);
    extractString(metadata, TIFF.EQUIPMENT_MAKE)
        .ifPresent(albumEntryDataBuilder::cameraManufacturer);
    extractInteger(metadata, TIFF.FOCAL_LENGTH).ifPresent(albumEntryDataBuilder::focalLength);
    extractDouble(metadata, TIFF.F_NUMBER).ifPresent(albumEntryDataBuilder::fNumber);

    extractString(metadata, Property.externalText(HttpHeaders.CONTENT_TYPE))
        .ifPresent(albumEntryDataBuilder::contentType);

    return albumEntryDataBuilder.build();
  }

  @Scheduled(fixedDelay = 60 * 1000, initialDelay = 2 * 1000)
  public void updateAlbumData() {
    try {
      final Optional<Long> albumCount =
          albumList
              .listAlbums()
              .filterWhen(
                  album -> {
                    final Mono<ObjectId> currentVersion = album.getAccess().getCurrentVersion();

                    final Mono<Optional<AlbumData>> loadedVersion =
                        albumDataRepository
                            .findById(album.getAlbumId())
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty());
                    return Mono.zip(currentVersion, loadedVersion)
                        .map(
                            t ->
                                t.getT2()
                                    .map(d1 -> d1.getCurrentVersion().equals(t.getT1()))
                                    .orElse(false))
                        .map(t -> !t)
                        .onErrorResume(
                            ex -> {
                              log.warn("Cannot load existing album", ex);
                              return Mono.just(true);
                            });
                  },
                  10)
              .flatMap(
                  album ->
                      Mono.zip(album.getAccess().getCurrentVersion(), album.getAccess().getName())
                          .flatMap(
                              TupleUtils.function(
                                  (currentVersion, currentName) ->
                                      albumDataEntryRepository
                                          .findByAlbumId(album.getAlbumId())
                                          // .log("find album by id " + album.getAlbumId())
                                          .collectMap(AlbumEntryData::getEntryId, e -> e)
                                          .onErrorResume(ex -> Mono.just(Collections.emptyMap()))
                                          .flatMap(
                                              entriesBefore ->
                                                  album
                                                      .getAccess()
                                                      .listFiles(IMAGE_FILE_FILTER)
                                                      .publishOn(Schedulers.elastic())
                                                      .flatMap(
                                                          gitFileEntry -> {
                                                            if (entriesBefore.containsKey(
                                                                gitFileEntry.getFileId())) {
                                                              final AlbumEntryData data =
                                                                  entriesBefore.get(
                                                                      gitFileEntry.getFileId());
                                                              return Mono.just(
                                                                  Tuples.of(true, data));
                                                            } else {
                                                              return album
                                                                  .getAccess()
                                                                  .entryMetdata(
                                                                      gitFileEntry.getFileId())
                                                                  .map(
                                                                      metadata ->
                                                                          createAlbumEntry(
                                                                              gitFileEntry,
                                                                              metadata,
                                                                              album.getAlbumId()))
                                                                  .doOnNext(
                                                                      d ->
                                                                          log.info(
                                                                              "Loaded metadata of "
                                                                                  + d
                                                                                      .getFilename()))
                                                                  .onErrorResume(
                                                                      ex -> {
                                                                        log.info(
                                                                            "Error on "
                                                                                + gitFileEntry
                                                                                    .getNameString(),
                                                                            ex);
                                                                        return Mono.empty();
                                                                      })
                                                                  .map(e -> Tuples.of(false, e));
                                                              // .flatMap(
                                                              //    albumDataEntryRepository::save);
                                                            }
                                                          },
                                                          30)
                                                      .publish(
                                                          in -> {
                                                            final Flux<AlbumEntryData> passThrough =
                                                                in.filter(Tuple2::getT1)
                                                                    .map(Tuple2::getT2);
                                                            final Flux<AlbumEntryData> stored =
                                                                in.filter(v -> !v.getT1())
                                                                    .map(Tuple2::getT2)
                                                                    .buffer(100)
                                                                    .publishOn(Schedulers.elastic())
                                                                    .flatMapIterable(
                                                                        entities -> {
                                                                          log.info(
                                                                              "Store "
                                                                                  + entities.size()
                                                                                  + " entries");
                                                                          try {
                                                                            final Iterable<
                                                                                    AlbumEntryData>
                                                                                albumEntryData =
                                                                                    syncAlbumDataEntryRepository
                                                                                        .saveAll(
                                                                                            entities);
                                                                            log.info(
                                                                                "Stored "
                                                                                    + entities
                                                                                        .size()
                                                                                    + " entries");

                                                                            return albumEntryData;
                                                                          } catch (Exception ex) {
                                                                            log.error(
                                                                                "Cannot store", ex);
                                                                            return Collections
                                                                                .emptyList();
                                                                          } finally {
                                                                            log.info("Done");
                                                                          }
                                                                        });
                                                            return Flux.merge(passThrough, stored);
                                                          })
                                                      .collect(
                                                          () ->
                                                              new AlbumStatisticsCollector(
                                                                  entriesBefore.keySet()),
                                                          AlbumStatisticsCollector::addAlbumData)
                                                      .publish(
                                                          statResult -> {
                                                            final Mono<Long> removeCount =
                                                                statResult
                                                                    .flatMapIterable(
                                                                        AlbumStatisticsCollector
                                                                            ::getRemainingEntries)
                                                                    .flatMap(
                                                                        id ->
                                                                            albumDataEntryRepository
                                                                                .deleteById(
                                                                                    AlbumEntryData
                                                                                        .createDocumentId(
                                                                                            album
                                                                                                .getAlbumId(),
                                                                                            id))
                                                                                .map(v -> 1)
                                                                                .defaultIfEmpty(1))
                                                                    .count();
                                                            final Mono<AlbumData> albumDataMono =
                                                                statResult
                                                                    .map(
                                                                        s ->
                                                                            s.fill(
                                                                                    AlbumData
                                                                                        .builder()
                                                                                        .repositoryId(
                                                                                            album
                                                                                                .getAlbumId())
                                                                                        .currentVersion(
                                                                                            currentVersion)
                                                                                        .name(
                                                                                            currentName))
                                                                                .build())
                                                                    .flatMap(
                                                                        albumDataRepository::save);

                                                            return Mono.zip(
                                                                removeCount, albumDataMono);
                                                          })))),
                  3)
              .log("repo")
              .count()
              .blockOptional();
      log.info("Album updated: " + albumCount);
    } catch (Exception ex) {
      log.warn("Cannot update data", ex);
    }
  }

  @Override
  public Flux<AlbumData> listAlbums() {
    return albumDataRepository.findAll();
  }

  @Override
  public Mono<AlbumData> readAlbum(final UUID id) {
    return albumDataRepository.findById(id);
  }

  @Override
  public Flux<AlbumEntryData> listEntries(final UUID id) {
    return albumDataEntryRepository.findByAlbumId(id);
  }

  @Override
  public Mono<AlbumEntryData> loadEntry(final UUID albumId, final ObjectId entryId) {
    return albumDataEntryRepository.findById(AlbumEntryData.createDocumentId(albumId, entryId));
  }

  private static class AlbumStatisticsCollector {
    private final LongSummaryStatistics timeSummary = new LongSummaryStatistics();
    private final LongAdder entryCount = new LongAdder();
    @Getter private final Set<ObjectId> remainingEntries;

    private AlbumStatisticsCollector(final Set<ObjectId> remainingEntries) {
      this.remainingEntries = new HashSet<>(remainingEntries);
    }

    public synchronized void addAlbumData(AlbumEntryData entry) {
      remainingEntries.remove(entry.getEntryId());
      if (entry.getCreateTime() != null) {
        timeSummary.accept(entry.getCreateTime().getEpochSecond());
      }
      entryCount.increment();
    }

    public synchronized AlbumData.AlbumDataBuilder fill(AlbumData.AlbumDataBuilder target) {
      if (timeSummary.getCount() > 0)
        target.createTime(Instant.ofEpochSecond((long) timeSummary.getAverage()));
      target.entryCount(entryCount.intValue());
      return target;
    }
  }
}
