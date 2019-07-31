package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.model.AlbumEntryKey;
import ch.bergturbenthal.raoa.libs.properties.Properties;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.FileImporter;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.Updater;
import java.io.*;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
import org.ehcache.config.units.EntryUnit;
import org.ehcache.config.units.MemoryUnit;
import org.ehcache.spi.serialization.Serializer;
import org.ehcache.spi.serialization.SerializerException;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

@Slf4j
@Service
public class BareAlbumList implements AlbumList {
  public static final Duration MAX_REPOSITORY_CACHE_TIME = Duration.ofMinutes(5);
  public static final Duration MAX_AUTOINDEX_CACHE_TIME = Duration.ofMinutes(1);
  public static final Charset CHARSET = StandardCharsets.UTF_8;
  public static final Pattern SPLIT_PATTERN = Pattern.compile(Pattern.quote(" "));
  private static final Collection<String> IMPORTING_TYPES =
      new HashSet<>(Arrays.asList("image/jpeg", "image/tiff", "application/mp4", "video/mp4"));
  private final Mono<SortedMap<Instant, UUID>> autoaddIndex;
  private final Mono<Map<UUID, GitAccess>> repositories;
  private final Scheduler ioScheduler;
  private Properties properties;

  public BareAlbumList(Properties properties) {
    this.properties = properties;
    ConcurrencyLimiter limiter = new ConcurrencyLimiter();

    final CacheManager cacheManager =
        CacheManagerBuilder.newCacheManagerBuilder()
            .with(CacheManagerBuilder.persistence(properties.getMetadataCache()))
            .build();
    cacheManager.init();

    final Cache<AlbumEntryKey, Metadata> metadataCache =
        cacheManager.createCache(
            "metadata",
            CacheConfigurationBuilder.newCacheConfigurationBuilder(
                    AlbumEntryKey.class,
                    Metadata.class,
                    ResourcePoolsBuilder.newResourcePoolsBuilder()
                        .heap(2000, EntryUnit.ENTRIES)
                        .disk(3, MemoryUnit.GB, true))
                .withKeySerializer(new AlbumEntryKeySerializer())
                .withValueSerializer(new MetadataSerializer()));

    ioScheduler = Schedulers.newElastic("io-scheduler");
    final Scheduler processScheduler = Schedulers.newElastic("process");
    repositories =
        listSubdirs(this.properties.getRepository().toPath())
            .subscribeOn(ioScheduler)
            .publishOn(processScheduler, 5)
            .<GitAccess>map(
                p ->
                    BareGitAccess.accessOf(
                        p, metadataCache, ioScheduler, processScheduler, limiter))
            .flatMap(p -> p.getMetadata().map(m -> Tuples.of(p, m)))
            .filter(t1 -> t1.getT2().getAlbumId() != null)
            .collectMap(t -> t.getT2().getAlbumId(), Tuple2::getT1)
            .cache(MAX_REPOSITORY_CACHE_TIME);
    autoaddIndex =
        repositories
            .flatMap(
                reps -> {
                  final Mono<SortedMap<Instant, UUID>> collect =
                      Flux.fromIterable(reps.entrySet())
                          .flatMap(
                              e ->
                                  e.getValue()
                                      .readAutoadd()
                                      .map(t -> new AutoaddEntry(t, e.getKey())))
                          .collect(
                              Collectors.toMap(
                                  AutoaddEntry::getTime,
                                  AutoaddEntry::getId,
                                  (a, b) -> b,
                                  TreeMap::new));
                  return collect;
                })
            .cache(MAX_AUTOINDEX_CACHE_TIME);
  }

  private static Flux<Path> listSubdirs(Path dir) {
    try {
      return Flux.fromIterable(Files.list(dir).collect(Collectors.toList()))
          .filter(e -> Files.isReadable(e) && Files.isExecutable(e) && Files.isDirectory(e))
          .flatMap(
              d -> {
                if (d.getFileName().toString().endsWith(".git")) {
                  return Flux.just(d);
                } else {
                  final Path dotGitDir = d.resolve(".git");
                  if (Files.isDirectory(dotGitDir)) {
                    return Flux.just(dotGitDir);
                  } else {
                    return listSubdirs(d);
                  }
                }
              });
    } catch (IOException e) {
      log.error("Cannot access directory " + dir, e);
    }
    return Flux.empty();
  }

  @Override
  public FileImporter createImporter() {
    return new FileImporter() {
      private final Map<UUID, Mono<Updater>> pendingUpdaters =
          Collections.synchronizedMap(new HashMap<>());

      @Override
      public void close() {
        for (Updater updater :
            Flux.fromIterable(pendingUpdaters.keySet())
                .map(k -> Optional.ofNullable(pendingUpdaters.remove(k)))
                .filter(Optional::isPresent)
                .flatMap(Optional::get)
                .toIterable()) {
          updater.close();
        }
      }

      public Mono<Boolean> importFile(final Path file) {
        return repositories
            .flatMap(
                reps -> {
                  try {
                    AutoDetectParser parser = new AutoDetectParser();
                    BodyContentHandler handler = new BodyContentHandler();
                    Metadata metadata = new Metadata();
                    final TikaInputStream inputStream = TikaInputStream.get(file);
                    parser.parse(inputStream, handler, metadata);
                    if (!IMPORTING_TYPES.contains(metadata.get(Metadata.CONTENT_TYPE))) {
                      log.info("Unsupported content type: " + metadata.get(Metadata.CONTENT_TYPE));
                      return Mono.just(false);
                    }
                    final Date createDate = metadata.getDate(TikaCoreProperties.CREATED);
                    if (createDate == null) {
                      log.info("No creation timestamp");
                      return Mono.just(false);
                    }
                    final Instant createTimestamp = createDate.toInstant();

                    final String prefix =
                        DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
                            .format(createTimestamp.atZone(ZoneId.systemDefault()));
                    final String targetFilename = prefix + "-" + file.getFileName().toString();
                    return albumOf(createTimestamp)
                        .flatMap(
                            repositoryId -> {
                              getAlbum(repositoryId)
                                  .flatMap(GitAccess::getName)
                                  .defaultIfEmpty("not found")
                                  .subscribe(name -> log.info("Import " + file + " to " + name));
                              return pendingUpdaters.computeIfAbsent(
                                  repositoryId, k -> reps.get(k).createUpdater().cache());
                            })
                        .flatMap(
                            updater ->
                                updater
                                    .importFile(file, targetFilename)
                                    .onErrorResume(
                                        e -> {
                                          log.warn("Cannot import file " + file, e);
                                          return Mono.just(false);
                                        }))
                        .defaultIfEmpty(false);

                  } catch (TikaException | SAXException | IOException e) {
                    log.warn("Cannot access file " + file, e);
                    return Mono.just(false);
                  }
                })
            .subscribeOn(ioScheduler);
      }

      @Override
      public Mono<Boolean> commitAll() {
        return Flux.fromIterable(pendingUpdaters.values())
            .flatMap(m -> m)
            .flatMap(Updater::commit)
            .reduce((b1, b2) -> b1 && b2)
            .defaultIfEmpty(Boolean.TRUE)
            .doFinally(signal -> pendingUpdaters.clear());
      }
    };
  }

  @Override
  public Flux<FoundAlbum> listAlbums() {
    return repositories
        .flatMapIterable(Map::entrySet)
        .map(e -> new FoundAlbum(e.getKey(), e.getValue()));
  }

  @Override
  public Mono<GitAccess> getAlbum(final UUID albumId) {
    return repositories
        .map(reps -> Optional.ofNullable(reps.get(albumId)))
        .filter(Optional::isPresent)
        .map(Optional::get);
  }

  private Mono<UUID> albumOf(final Instant timestamp) {
    return autoaddIndex
        .map(i -> i.headMap(timestamp))
        .filter(t -> !t.isEmpty())
        .map(t -> t.get(t.lastKey()));
  }

  @Value
  private static class AutoaddEntry {
    private Instant time;
    private UUID id;
  }

  private static class AlbumEntryKeySerializer implements Serializer<AlbumEntryKey> {
    @Override
    public ByteBuffer serialize(final AlbumEntryKey object) throws SerializerException {
      final ByteBuffer buffer = ByteBuffer.allocate(36);
      buffer.putLong(object.getAlbum().getMostSignificantBits());
      buffer.putLong(object.getAlbum().getLeastSignificantBits());
      object.getEntry().copyRawTo(buffer);
      buffer.rewind();
      return buffer;
    }

    @Override
    public AlbumEntryKey read(final ByteBuffer binary) throws SerializerException {
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

  private class MetadataSerializer implements Serializer<Metadata> {
    @Override
    public ByteBuffer serialize(final Metadata object) throws SerializerException {
      final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      final PrintWriter printWriter =
          new PrintWriter(new OutputStreamWriter(byteArrayOutputStream, CHARSET));
      for (String keyName : object.names()) {
        final String[] values = object.getValues(keyName);
        if (values.length > 0) {
          printWriter.print(URLEncoder.encode(keyName, CHARSET));
          for (String value : values) {
            printWriter.print(' ');
            printWriter.print(URLEncoder.encode(value, CHARSET));
          }
          printWriter.println();
        }
      }
      printWriter.close();
      return ByteBuffer.wrap(byteArrayOutputStream.toByteArray());
    }

    @Override
    public Metadata read(final ByteBuffer binary)
        throws ClassNotFoundException, SerializerException {
      final Metadata metadata = new Metadata();
      final BufferedReader bufferedReader =
          new BufferedReader(
              new InputStreamReader(new ByteArrayInputStream(binary.array()), CHARSET));
      try {
        while (true) {
          final String line = bufferedReader.readLine();
          if (line == null) break;
          final String[] parts = SPLIT_PATTERN.split(line);
          String key = parts[0];
          if (parts.length < 2) {
            metadata.add(key, "");
            continue;
          }
          for (int i = 1; i < parts.length; i++) {
            String value = parts[i];
            metadata.add(key, value);
          }
        }
        return metadata;
      } catch (IOException e) {
        throw new SerializerException("Cannot read data", e);
      }
    }

    @Override
    public boolean equals(final Metadata object, final ByteBuffer binary)
        throws ClassNotFoundException, SerializerException {
      return object.equals(read(binary));
    }
  }
}
