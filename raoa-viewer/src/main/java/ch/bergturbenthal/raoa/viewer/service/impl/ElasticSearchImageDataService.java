package ch.bergturbenthal.raoa.viewer.service.impl;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.viewer.model.elasticsearch.AlbumData;
import ch.bergturbenthal.raoa.viewer.model.elasticsearch.AlbumEntryData;
import ch.bergturbenthal.raoa.viewer.repository.AlbumDataEntryRepository;
import ch.bergturbenthal.raoa.viewer.repository.AlbumDataRepository;
import ch.bergturbenthal.raoa.viewer.service.ImageDataService;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
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

@Slf4j
@Service
public class ElasticSearchImageDataService implements ImageDataService {
  public static final TreeFilter IMAGE_FILE_FILTER =
      OrTreeFilter.create(
          new TreeFilter[] {
            PathSuffixFilter.create(".jpg"),
            PathSuffixFilter.create(".jpeg"),
            PathSuffixFilter.create(".JPG"),
            PathSuffixFilter.create(".JPEG")
          });
  private final AlbumDataRepository albumDataRepository;
  private final AlbumDataEntryRepository albumDataEntryRepository;
  private final AlbumList albumList;

  public ElasticSearchImageDataService(
      final AlbumDataRepository albumDataRepository,
      final AlbumDataEntryRepository albumDataEntryRepository,
      final AlbumList albumList) {
    this.albumDataRepository = albumDataRepository;
    this.albumDataEntryRepository = albumDataEntryRepository;
    this.albumList = albumList;
    log.info("Initialized");
  }

  @Scheduled(fixedDelay = 60 * 1000, initialDelay = 2 * 1000)
  public void updateAlbumData() {

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
                      .map(t -> !t);
                },
                2)
            .flatMap(
                album ->
                    album
                        .getAccess()
                        .getCurrentVersion()
                        .flatMap(
                            currentVersion ->
                                albumDataEntryRepository
                                    .findByAlbumId(album.getAlbumId())
                                    .log("find album by id " + album.getAlbumId())
                                    .map(AlbumEntryData::getEntryId)
                                    .collect(Collectors.toSet())
                                    .onErrorResume(ex -> Mono.just(Collections.emptySet()))
                                    .flatMap(
                                        entriesBefore ->
                                            album
                                                .getAccess()
                                                .listFiles(IMAGE_FILE_FILTER)
                                                .flatMap(
                                                    gitFileEntry ->
                                                        entriesBefore.contains(
                                                                gitFileEntry.getFileId())
                                                            ? Mono.just(gitFileEntry.getFileId())
                                                            : album
                                                                .getAccess()
                                                                .entryMetdata(
                                                                    gitFileEntry.getFileId())
                                                                .map(
                                                                    metadata ->
                                                                        createAlbumEntry(
                                                                            gitFileEntry,
                                                                            metadata,
                                                                            album.getAlbumId()))
                                                                .flatMap(
                                                                    albumDataEntryRepository::save)
                                                                .map(AlbumEntryData::getEntryId),
                                                    10)
                                                .collect(
                                                    () ->
                                                        (Set<ObjectId>)
                                                            new HashSet<>(entriesBefore),
                                                    Set::remove)
                                                .flatMapIterable(id -> id)
                                                .flatMap(
                                                    id ->
                                                        albumDataEntryRepository.deleteById(
                                                            AlbumEntryData.createDocumentId(
                                                                album.getAlbumId(), id)),
                                                    10)
                                                .count())
                                    .flatMap(
                                        removeCount ->
                                            albumDataRepository.save(
                                                AlbumData.builder()
                                                    .repositoryId(album.getAlbumId())
                                                    .currentVersion(currentVersion)
                                                    .build()))),
                2)
            .log("repo")
            .count()
            .blockOptional();
    log.info("Album updated: " + albumCount);
  }

  public AlbumEntryData createAlbumEntry(
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

  private static Optional<Integer> extractTargetWidth(final Metadata m) {
    return Optional.ofNullable(m.get(TIFF.ORIENTATION))
        .map(Integer::valueOf)
        .flatMap(
            o -> {
              if (o <= 4) {
                return Optional.ofNullable(m.getInt(TIFF.IMAGE_WIDTH));
              } else {
                return Optional.ofNullable(m.getInt(TIFF.IMAGE_LENGTH));
              }
            });
  }

  private static Optional<Integer> extractTargetHeight(final Metadata m) {
    return Optional.ofNullable(m.get(TIFF.ORIENTATION))
        .map(Integer::valueOf)
        .flatMap(
            o -> {
              if (o <= 4) {
                return Optional.ofNullable(m.getInt(TIFF.IMAGE_LENGTH));
              } else {
                return Optional.ofNullable(m.getInt(TIFF.IMAGE_WIDTH));
              }
            });
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
}
