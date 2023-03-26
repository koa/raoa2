package ch.bergturbenthal.raoa.elastic.service.impl;

import ch.bergturbenthal.raoa.elastic.model.AlbumData;
import ch.bergturbenthal.raoa.elastic.model.AlbumEntryData;
import ch.bergturbenthal.raoa.elastic.model.AuthenticationId;
import ch.bergturbenthal.raoa.elastic.model.Group;
import ch.bergturbenthal.raoa.elastic.model.PersonalUserData;
import ch.bergturbenthal.raoa.elastic.model.RequestAccess;
import ch.bergturbenthal.raoa.elastic.model.TemporaryPassword;
import ch.bergturbenthal.raoa.elastic.model.User;
import ch.bergturbenthal.raoa.elastic.repository.AccessRequestRepository;
import ch.bergturbenthal.raoa.elastic.repository.AlbumDataEntryRepository;
import ch.bergturbenthal.raoa.elastic.repository.AlbumDataRepository;
import ch.bergturbenthal.raoa.elastic.repository.GroupRepository;
import ch.bergturbenthal.raoa.elastic.repository.SyncAlbumDataEntryRepository;
import ch.bergturbenthal.raoa.elastic.repository.TemporaryPasswordRepository;
import ch.bergturbenthal.raoa.elastic.repository.UserRepository;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.elastic.service.UserManager;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.AsyncService;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.impl.XmpWrapper;
import com.adobe.internal.xmp.XMPMeta;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.map.LRUMap;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Property;
import org.apache.tika.metadata.TIFF;
import org.apache.tika.metadata.TikaCoreProperties;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.treewalk.filter.OrTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.function.TupleUtils;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;
import reactor.util.retry.Retry;

@Slf4j
@Service
public class ElasticSearchDataViewService implements DataViewService {
  public static final TreeFilter MEDIA_FILE_FILTER =
      OrTreeFilter.create(
          new TreeFilter[] {
            PathSuffixFilter.create(".jpg"),
            PathSuffixFilter.create(".jpeg"),
            PathSuffixFilter.create(".JPG"),
            PathSuffixFilter.create(".JPEG"),
            PathSuffixFilter.create(".nef"),
            PathSuffixFilter.create(".NEF"),
            PathSuffixFilter.create(".mp4"),
            PathSuffixFilter.create(".MP4") // ,
            // PathSuffixFilter.create(".mkv")
          });
  public static final TreeFilter IMAGE_FILE_FILTER =
      OrTreeFilter.create(
          new TreeFilter[] {
            PathSuffixFilter.create(".jpg"),
            PathSuffixFilter.create(".jpeg"),
            PathSuffixFilter.create(".JPG"),
            PathSuffixFilter.create(".JPEG"),
            PathSuffixFilter.create(".nef"),
            PathSuffixFilter.create(".NEF")
          });
  public static final Duration CACHE_TIME = Duration.ofSeconds(10);
  private static final Pattern NUMBER_PATTERN = Pattern.compile("[0-9.]+");
  public static TreeFilter XMP_FILE_FILTER = PathSuffixFilter.create(".xmp");
  private final UUID virtualSuperuserId = UUID.randomUUID();
  private final AlbumDataRepository albumDataRepository;
  private final AlbumDataEntryRepository albumDataEntryRepository;
  private final AlbumList albumList;
  private final SyncAlbumDataEntryRepository syncAlbumDataEntryRepository;
  private final UserRepository userRepository;
  private final GroupRepository groupRepository;
  private final AccessRequestRepository accessRequestRepository;
  private final UserManager userManager;
  private final ExecutorService ioExecutorService =
      Executors.newFixedThreadPool(3, new CustomizableThreadFactory("elastic-data-view"));
  private final Map<UUID, Mono<User>> userCache = Collections.synchronizedMap(new LRUMap<>(20));
  private final Map<UUID, Mono<Group>> groupCache = Collections.synchronizedMap(new LRUMap<>(200));
  private final ElasticsearchRestTemplate elasticsearchTemplate;
  private final AtomicReference<ObjectId> lastMetaVersion = new AtomicReference<>();
  private final TemporaryPasswordRepository temporaryPasswordRepository;
  private final AsyncService asyncService;

  public ElasticSearchDataViewService(
      final AlbumDataRepository albumDataRepository,
      final AlbumDataEntryRepository albumDataEntryRepository,
      final AlbumList albumList,
      final SyncAlbumDataEntryRepository syncAlbumDataEntryRepository,
      final UserRepository userRepository,
      final GroupRepository groupRepository,
      final AccessRequestRepository accessRequestRepository,
      final UserManager userManager,
      final ElasticsearchRestTemplate elasticsearchTemplate,
      final TemporaryPasswordRepository temporaryPasswordRepository,
      final AsyncService asyncService) {
    this.albumDataRepository = albumDataRepository;
    this.albumDataEntryRepository = albumDataEntryRepository;
    this.albumList = albumList;
    this.syncAlbumDataEntryRepository = syncAlbumDataEntryRepository;
    this.userRepository = userRepository;
    this.groupRepository = groupRepository;
    this.accessRequestRepository = accessRequestRepository;
    this.userManager = userManager;
    this.elasticsearchTemplate = elasticsearchTemplate;
    this.temporaryPasswordRepository = temporaryPasswordRepository;
    this.asyncService = asyncService;
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
      final GitAccess.GitFileEntry gitFileEntry,
      final Metadata metadata,
      final XMPMeta xmpMeta,
      final UUID albumId) {
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
    extractDouble(metadata, TIFF.FOCAL_LENGTH).ifPresent(albumEntryDataBuilder::focalLength);
    extractDouble(metadata, TIFF.F_NUMBER).ifPresent(albumEntryDataBuilder::fNumber);
    extractDouble(metadata, TIFF.EXPOSURE_TIME).ifPresent(albumEntryDataBuilder::exposureTime);
    extractInteger(metadata, TIFF.ISO_SPEED_RATINGS)
        .or(() -> extractInteger(metadata, Property.internalInteger("ISO Speed Ratings")))
        .ifPresent(albumEntryDataBuilder::isoSpeedRatings);

    extractString(metadata, Property.internalInteger("Focal Length 35"))
        .map(v -> v.split(" ")[0])
        .map(String::trim)
        .filter(v -> NUMBER_PATTERN.matcher(v).matches())
        .map(Double::valueOf)
        .ifPresent(albumEntryDataBuilder::focalLength35);

    extractString(metadata, Property.externalText(HttpHeaders.CONTENT_TYPE))
        .ifPresent(albumEntryDataBuilder::contentType);
    final Optional<Double> lat = extractDouble(metadata, TikaCoreProperties.LATITUDE);
    final Optional<Double> lon = extractDouble(metadata, TikaCoreProperties.LONGITUDE);
    if (lat.isPresent() && lon.isPresent()) {
      albumEntryDataBuilder.captureCoordinates(new GeoPoint(lat.get(), lon.get()));
    }
    if (xmpMeta != null) {
      final XmpWrapper xmpWrapper = new XmpWrapper(xmpMeta);
      albumEntryDataBuilder.description(xmpWrapper.readDescription());
      albumEntryDataBuilder.rating(xmpWrapper.readRating());
      albumEntryDataBuilder.keywords(new HashSet<>(xmpWrapper.readKeywords()));
    }

    return albumEntryDataBuilder.build();
  }

  // @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 5 * 1000)
  public void updateAlbumData() {
    try {
      final Optional<Long> albumCount = updateAlbums(this.albumList.listAlbums()).blockOptional();
      log.info("Album updated: " + albumCount);
    } catch (Exception ex) {
      log.warn("Cannot update data", ex);
    }
  }

  // @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 2 * 1000)
  public void doUpdateUserData() {
    updateUserData().block();
  }

  @Override
  public Mono<Void> updateUserData() {
    createIndexIfMissing(Group.class);
    createIndexIfMissing(User.class);
    return userManager
        .getMetaVersion()
        .flatMap(
            version ->
                Objects.equals(lastMetaVersion.get(), version)
                    ? Mono.empty()
                    : Flux.merge(
                            userRepository
                                .findAll()
                                .retryWhen(Retry.backoff(10, Duration.ofSeconds(20)))
                                .onErrorResume(
                                    ex -> {
                                      log.warn("Cannot load existing users", ex);
                                      return Flux.empty();
                                    })
                                .collectMap(User::getId, Function.identity())
                                .flatMap(
                                    (Map<UUID, User> existingUsers) ->
                                        userManager
                                            .listUsers()
                                            .flatMap(
                                                storedUser ->
                                                    Objects.equals(
                                                            existingUsers.get(storedUser.getId()),
                                                            storedUser)
                                                        ? Mono.just(storedUser.getId())
                                                        : userRepository
                                                            .save(storedUser)
                                                            .map(User::getId))
                                            .<Set<UUID>>collect(
                                                () -> new HashSet<>(existingUsers.keySet()),
                                                Set::remove))
                                .flatMapIterable(Function.identity())
                                .flatMap(userRepository::deleteById)
                                .then(),
                            groupRepository
                                .findAll()
                                .retryWhen(Retry.backoff(10, Duration.ofSeconds(20)))
                                .onErrorResume(
                                    ex -> {
                                      log.warn("Cannot load existing groups", ex);
                                      return Flux.empty();
                                    })
                                .collectMap(Group::getId, Function.identity())
                                .flatMap(
                                    existingGroups ->
                                        userManager
                                            .listGroups()
                                            .flatMap(
                                                storedGroup ->
                                                    Objects.equals(
                                                            existingGroups.get(storedGroup.getId()),
                                                            storedGroup)
                                                        ? Mono.just(storedGroup.getId())
                                                        : groupRepository
                                                            .save(storedGroup)
                                                            .map(Group::getId))
                                            .collect(
                                                () -> new HashSet<>(existingGroups.keySet()),
                                                Set::remove))
                                .flatMapIterable(Function.identity())
                                .flatMap(groupRepository::deleteById)
                                .then())
                        .doOnComplete(() -> lastMetaVersion.set(version))
                        .then());
  }

  private void createIndexIfMissing(final Class<?> clazz) {
    final IndexOperations indexOperations = elasticsearchTemplate.indexOps(clazz);
    if (!indexOperations.exists()) {
      indexOperations.create();
    }
  }

  @Override
  public Mono<Long> updateAlbums(final Flux<AlbumList.FoundAlbum> albumList) {
    return albumList
        /*.filterWhen(
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
        10)*/
        .collectList()
        .flatMapIterable(
            l -> {
              final List<AlbumList.FoundAlbum> albums = new ArrayList<>(l);
              Collections.shuffle(albums);
              return albums;
            })
        .flatMap(
            album -> {
              final GitAccess access = album.getAccess();
              return Mono.zip(access.getCurrentVersion(), access.getName())
                  .flatMap(
                      TupleUtils.function(
                          (currentVersion, currentName) ->
                              albumDataEntryRepository
                                  .findByAlbumId(album.getAlbumId())
                                  // .log("find album by id " + album.getAlbumId())
                                  .collectMap(AlbumEntryData::getEntryId, Function.identity())
                                  .onErrorResume(ex -> Mono.just(Collections.emptyMap()))
                                  .flatMap(
                                      entriesBefore ->
                                          access
                                              .listFiles(XMP_FILE_FILTER)
                                              .flatMap(
                                                  xmpGitEntry ->
                                                      access
                                                          .readObject(xmpGitEntry.getFileId())
                                                          .flatMap(access::readXmpMeta)
                                                          .map(
                                                              meta ->
                                                                  Tuples.of(
                                                                      stripXmpTail(
                                                                          xmpGitEntry
                                                                              .getNameString()),
                                                                      meta)))
                                              .collectMap(Tuple2::getT1, Tuple2::getT2)
                                              // .log("xmp meta")
                                              .flatMap(
                                                  xmpMetadata ->
                                                      access
                                                          .listFiles(MEDIA_FILE_FILTER)
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
                                                                  return access
                                                                      .entryMetdata(
                                                                          gitFileEntry.getFileId())
                                                                      .map(
                                                                          metadata ->
                                                                              createAlbumEntry(
                                                                                  gitFileEntry,
                                                                                  metadata,
                                                                                  xmpMetadata.get(
                                                                                      gitFileEntry
                                                                                          .getNameString()),
                                                                                  album
                                                                                      .getAlbumId()))
                                                                      /*.doOnNext(
                                                                      d ->
                                                                          log.info(
                                                                              "Loaded metadata of "
                                                                                  + d.getFilename()))*/
                                                                      .onErrorResume(
                                                                          ex -> {
                                                                            log.info(
                                                                                "Error on "
                                                                                    + gitFileEntry
                                                                                        .getNameString(),
                                                                                ex);
                                                                            return Mono.empty();
                                                                          })
                                                                      .map(
                                                                          e -> Tuples.of(false, e));
                                                                }
                                                              },
                                                              20)
                                                          .publish(
                                                              in -> {
                                                                final Flux<AlbumEntryData>
                                                                    passThrough =
                                                                        in.filter(Tuple2::getT1)
                                                                            .map(Tuple2::getT2);
                                                                final Flux<AlbumEntryData> stored =
                                                                    in.filter(v -> !v.getT1())
                                                                        .map(Tuple2::getT2)
                                                                        .buffer(100)
                                                                        .flatMap(
                                                                            entities ->
                                                                                asyncService
                                                                                    .asyncFlux(
                                                                                        result ->
                                                                                            syncAlbumDataEntryRepository
                                                                                                .saveAll(
                                                                                                    entities)
                                                                                                .forEach(
                                                                                                    result)));
                                                                return Flux.merge(
                                                                    passThrough, stored);
                                                              })
                                                          .collect(
                                                              () ->
                                                                  new AlbumStatisticsCollector(
                                                                      entriesBefore.keySet()),
                                                              AlbumStatisticsCollector
                                                                  ::addAlbumData)
                                                          .publish(
                                                              statResult ->
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
                                                                                  .thenReturn(1))
                                                                      .count())))));
            },
            2)
        .count();
  }

  private String stripXmpTail(final String filename) {
    return filename.substring(0, filename.length() - 4);
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

  @Override
  public Flux<User> findUserForAuthentication(final AuthenticationId authenticationId) {
    return userRepository
        .findByAuthenticationsAuthorityAndAuthenticationsId(
            authenticationId.getAuthority(), authenticationId.getId())
        .filter(u -> u.getAuthentications().contains(authenticationId))
        .retryWhen(Retry.backoff(10, Duration.ofSeconds(20)))
        .onErrorResume(
            ex -> {
              log.warn("Cannot load user by authentication id " + authenticationId, ex);
              return Flux.empty();
            });
  }

  @Override
  public Mono<User> findUserById(final UUID id) {
    return userCache
        .computeIfAbsent(id, userid -> userRepository.findById(userid).cache(CACHE_TIME))
        .switchIfEmpty(
            Mono.defer(
                () -> {
                  if (id.equals(virtualSuperuserId)) {
                    return Mono.just(
                        User.builder()
                            .authentications(Collections.emptySet())
                            .id(virtualSuperuserId)
                            .superuser(true)
                            .userData(
                                PersonalUserData.builder().comment("Virtual superuser").build())
                            .build());
                  }
                  return Mono.empty();
                }));
  }

  @Override
  public Mono<Group> findGroupById(final UUID id) {
    return groupCache.computeIfAbsent(
        id, groupId -> groupRepository.findById(groupId).cache(CACHE_TIME));
  }

  @Override
  public Flux<User> listUserForAlbum(final UUID albumId) {
    return listGroups()
        .filter(g -> g.getVisibleAlbums().contains(albumId))
        .map(Group::getId)
        .collect(Collectors.toSet())
        .flatMapMany(
            groups ->
                listUsers()
                    .filter(
                        u ->
                            u.isSuperuser()
                                || u.getVisibleAlbums().contains(albumId)
                                || u.getGroupMembership().stream().anyMatch(groups::contains)));
  }

  @Override
  public Mono<RequestAccess> getPendingRequest(final AuthenticationId id) {
    return accessRequestRepository
        .findById(RequestAccess.concatId(id))
        .onErrorResume(
            ex -> {
              log.warn("Error listing Users", ex);
              return Mono.empty();
            });
  }

  @Override
  public Mono<RequestAccess> requestAccess(final RequestAccess request) {
    return accessRequestRepository.save(request);
  }

  @Override
  public Flux<RequestAccess> listAllRequestedAccess() {
    return accessRequestRepository
        .findAll()
        .onErrorResume(
            ex -> {
              log.warn("Cannot load pending requests", ex);
              return Flux.empty();
            });
  }

  @Override
  public Mono<Void> removePendingAccessRequest(final AuthenticationId id) {
    return accessRequestRepository.deleteById(RequestAccess.concatId(id));
  }

  @Override
  public Flux<User> listUsers() {
    return Flux.merge(userRepository.findBySuperuser(true), userRepository.findBySuperuser(false))
        .onErrorResume(
            ex -> {
              log.warn("Error listing Users", ex);
              return Flux.empty();
            });
  }

  @Override
  public Flux<Group> listGroups() {
    return groupRepository.findAll();
  }

  @Override
  public Mono<AlbumEntryData> updateKeyword(
      final UUID albumId, final ObjectId entryId, final XMPMeta xmpMeta) {
    // patch keywords temporary until coordinator has updated all data
    Collection<String> newKeywords = new XmpWrapper(xmpMeta).readKeywords();
    return loadEntry(albumId, entryId)
        .map(data -> data.toBuilder().keywords(new HashSet<>(newKeywords)).xmpFileId(null).build())
        .flatMap(albumDataEntryRepository::save);
  }

  @Override
  public Mono<TemporaryPassword> createTemporaryPassword(
      final UUID user, final String title, final String password, final Instant validUntil) {
    return temporaryPasswordRepository.save(
        TemporaryPassword.builder()
            .id(createTemporaryPwKey(user, title))
            .userId(user)
            .title(title)
            .password(password)
            .validUntil(validUntil)
            .build());
  }

  @Override
  public Mono<User> findAndValidateTemporaryPassword(final UUID user, final String password) {
    final Instant now = Instant.now();
    return temporaryPasswordRepository
        .findByUserIdAndPassword(user, password)
        .filter(
            e ->
                e.getValidUntil().isAfter(now)
                    && e.getValidUntil().isBefore(now.plus(1000, ChronoUnit.DAYS)))
        .flatMap(tempPw -> findUserById(user));
  }

  @Override
  public Flux<TemporaryPassword> findTemporaryPasswordsByUser(final UUID user) {
    return temporaryPasswordRepository.findByUserId(user);
  }

  @Override
  public Mono<Void> deleteTemporaryPasswordsByUser(final UUID userId, final String title) {
    return temporaryPasswordRepository.deleteById(createTemporaryPwKey(userId, title));
  }

  @NotNull
  private static String createTemporaryPwKey(final UUID userId, final String title) {
    return userId.toString() + "-" + title;
  }
}
