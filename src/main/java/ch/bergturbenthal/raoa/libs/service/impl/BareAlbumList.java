package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.model.AlbumEntryKey;
import ch.bergturbenthal.raoa.libs.properties.Properties;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.FileImporter;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.Updater;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
import org.eclipse.jgit.lib.ObjectId;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.spi.serialization.Serializer;
import org.ehcache.spi.serialization.SerializerException;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

@Slf4j
@Service
public class BareAlbumList implements AlbumList {
  public static final Duration MAX_REPOSITORY_CACHE_TIME = Duration.ofMinutes(5);
  private static final Collection<String> IMPORTING_TYPES =
      new HashSet<>(Arrays.asList("image/jpeg", "image/tiff", "application/mp4", "video/mp4"));
  private final Supplier<SortedMap<Instant, UUID>> autoaddIndex;
  private final Supplier<Map<UUID, GitAccess>> repositories;
  private Properties properties;

  public BareAlbumList(Properties properties) {
    this.properties = properties;

    final CacheManager cacheManager = CacheManagerBuilder.newCacheManagerBuilder().build();
    cacheManager.init();
    final Cache<AlbumEntryKey, Metadata> metadataCache =
        cacheManager.createCache(
            "metadata",
            CacheConfigurationBuilder.newCacheConfigurationBuilder(
                    AlbumEntryKey.class, Metadata.class, ResourcePoolsBuilder.heap(20000))
                .withKeySerializer(new AlbumEntryKeySerializer()));

    final AtomicReference<Map<UUID, GitAccess>> cachedRepositories = new AtomicReference<>();
    final AtomicReference<SortedMap<Instant, UUID>> cachedIndex = new AtomicReference<>();
    final AtomicReference<Instant> lastScanTime = new AtomicReference<>(Instant.MIN);
    final Object repositoryScanLock = new Object();
    repositories =
        () -> {
          synchronized (repositoryScanLock) {
            final Map<UUID, GitAccess> cachedValue = cachedRepositories.get();
            if (cachedValue != null
                && Duration.between(Instant.now(), lastScanTime.get())
                    .minus(MAX_REPOSITORY_CACHE_TIME)
                    .isNegative()) return cachedValue;

            final Stream<BareGitAccess> bareGitAccessStream =
                listSubdirs(this.properties.getRepository().toPath())
                    .map(
                        p -> {
                          try {
                            return BareGitAccess.accessOf(p, metadataCache);
                          } catch (IOException e) {
                            throw new RuntimeException("Cannot open repository of " + p, e);
                          }
                        })
                    .filter(p -> p.getMetadata() != null)
                    .filter(p -> p.getMetadata().getAlbumId() != null);
            final Map<UUID, GitAccess> newValue =
                bareGitAccessStream.collect(
                    HashMap::new,
                    (hashMap, bareGitAccess) ->
                        hashMap.put(bareGitAccess.getMetadata().getAlbumId(), bareGitAccess),
                    HashMap::putAll);

            lastScanTime.set(Instant.now());
            cachedRepositories.set(newValue);
            cachedIndex.set(null);
            return newValue;
          }
        };
    autoaddIndex =
        () -> {
          final SortedMap<Instant, UUID> cachedValue = cachedIndex.get();
          if (cachedValue != null) return cachedValue;
          final TreeMap<Instant, UUID> newValue =
              repositories.get().entrySet().stream()
                  .flatMap(
                      e -> e.getValue().readAutoadd().map(t -> new AutoaddEntry(t, e.getKey())))
                  .collect(
                      Collectors.toMap(
                          AutoaddEntry::getTime, AutoaddEntry::getId, (a, b) -> b, TreeMap::new));
          cachedIndex.set(newValue);
          return newValue;
        };
  }

  private static Stream<Path> listSubdirs(Path dir) {
    try {
      return Files.list(dir)
          .filter(e -> Files.isDirectory(e))
          .flatMap(
              d -> {;
                if (d.getFileName().toString().endsWith(".git")) {
                  return Stream.of(d);
                } else {
                  final Path dotGitDir = d.resolve(".git");
                  if (Files.isDirectory(dotGitDir)) {
                    return Stream.of(dotGitDir);
                  } else {
                    return listSubdirs(d);
                  }
                }
              });
    } catch (IOException e) {
      log.error("Cannot access directory " + dir, e);
    }
    return Stream.empty();
  }

  @Override
  public FileImporter createImporter() {
    return new FileImporter() {
      private final Map<UUID, Updater> pendingUpdaters = new HashMap<>();

      @Override
      public synchronized boolean importFile(final Path file) throws IOException {
        try {
          AutoDetectParser parser = new AutoDetectParser();
          BodyContentHandler handler = new BodyContentHandler();
          Metadata metadata = new Metadata();
          final TikaInputStream inputStream = TikaInputStream.get(file);
          parser.parse(inputStream, handler, metadata);
          if (!IMPORTING_TYPES.contains(metadata.get(Metadata.CONTENT_TYPE))) {
            log.info("Unsupported content type: " + metadata.get(Metadata.CONTENT_TYPE));
            return false;
          }
          final Date createDate = metadata.getDate(TikaCoreProperties.CREATED);
          if (createDate == null) {
            log.info("No creation timestamp");
            return false;
          }
          final Instant createTimestamp = createDate.toInstant();

          final String prefix =
              DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
                  .format(createTimestamp.atZone(ZoneId.systemDefault()));
          final String targetFilename = prefix + "-" + file.getFileName().toString();
          return albumOf(createTimestamp)
              .map(
                  repositoryId -> {
                    log.info(
                        "Import "
                            + file
                            + " to "
                            + getAlbum(repositoryId).map(GitAccess::getName).orElse("not found"));
                    return pendingUpdaters.computeIfAbsent(
                        repositoryId, k -> repositories.get().get(k).createUpdater());
                  })
              .map(
                  foundRepository -> {
                    try {
                      return foundRepository.importFile(file, targetFilename);
                    } catch (IOException e) {
                      log.warn("Cannot import file " + file, e);
                      return false;
                    }
                  })
              .orElse(false);

        } catch (TikaException | SAXException e) {
          log.warn("Cannot access file " + file, e);
          return false;
        }
      }

      @Override
      public synchronized boolean commitAll() {
        try {
          return pendingUpdaters.values().stream()
              .map(Updater::commit)
              .reduce((b1, b2) -> b1 && b2)
              .orElse(true);
        } finally {
          pendingUpdaters.clear();
        }
      }
    };
  }

  @Override
  public Stream<FoundAlbum> listAlbums() {
    return repositories.get().entrySet().stream()
        .map(e -> new FoundAlbum(e.getKey(), e.getValue()));
  }

  @Override
  public Optional<GitAccess> getAlbum(final UUID albumId) {
    return Optional.ofNullable(repositories.get().get(albumId));
  }

  private Optional<UUID> albumOf(final Instant timestamp) {
    final SortedMap<Instant, UUID> headMap = autoaddIndex.get().headMap(timestamp);
    if (headMap.isEmpty()) return Optional.empty();
    return Optional.of(autoaddIndex.get().get(headMap.lastKey()));
  }

  @Value
  private static class AutoaddEntry {
    private Instant time;
    private UUID id;
  }

  private static class AlbumEntryKeySerializer implements Serializer<AlbumEntryKey> {
    @Override
    public ByteBuffer serialize(final AlbumEntryKey object) throws SerializerException {
      final ByteBuffer buffer = ByteBuffer.allocate(52);
      buffer.putLong(object.getAlbum().getMostSignificantBits());
      buffer.putLong(object.getAlbum().getLeastSignificantBits());
      object.getEntry().copyRawTo(buffer);
      return buffer;
    }

    @Override
    public AlbumEntryKey read(final ByteBuffer binary)
        throws ClassNotFoundException, SerializerException {
      final long msb = binary.getLong();
      final long lsb = binary.getLong();
      final UUID albumId = new UUID(msb, lsb);
      byte[] buffer = new byte[20];
      binary.get(buffer);
      final ObjectId entryId = ObjectId.fromRaw(buffer);
      return new AlbumEntryKey(albumId, entryId);
    }

    @Override
    public boolean equals(final AlbumEntryKey object, final ByteBuffer binary)
        throws ClassNotFoundException, SerializerException {
      return object.equals(read(binary));
    }
  }
}
