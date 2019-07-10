package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.properties.Properties;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.FileImporter;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.Updater;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

@Slf4j
@Service
public class BareAlbumList implements AlbumList {
  private static final Collection<String> IMPORTING_TYPES =
      new HashSet<>(Arrays.asList("image/jpeg", "image/tiff", "application/mp4", "video/mp4"));
  private final Map<Path, UUID> repositoryIds = new ConcurrentHashMap<>();
  private final SortedMap<Instant, UUID> autoaddIndex;
  private final Map<UUID, GitAccess> repositories;

  public BareAlbumList(Properties properties) {

    repositories =
        listSubdirs(properties.getRepository().toPath())
            .collect(
                Collectors.toMap(
                    this::idOfRepository,
                    p -> {
                      try {
                        return BareGitAccess.accessOf(p);
                      } catch (IOException e) {
                        throw new RuntimeException("Cannot open repository of " + p, e);
                      }
                    }));
    autoaddIndex =
        repositories.entrySet().stream()
            .flatMap(e -> e.getValue().readAutoadd().map(t -> new AutoaddEntry(t, e.getKey())))
            .collect(
                Collectors.toMap(
                    AutoaddEntry::getTime, AutoaddEntry::getId, (a, b) -> b, TreeMap::new));
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

  private UUID idOfRepository(Path path) {
    return repositoryIds.computeIfAbsent(path, k -> UUID.randomUUID());
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
                    log.info("Import " + file + " to " + repositoryId);
                    return pendingUpdaters.computeIfAbsent(
                        repositoryId, k -> repositories.get(k).createUpdater());
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
    return repositories.entrySet().stream().map(e -> new FoundAlbum(e.getKey(), e.getValue()));
  }

  @Override
  public Optional<GitAccess> getAlbum(final UUID albumId) {
    return Optional.ofNullable(repositories.get(albumId));
  }

  private Optional<UUID> albumOf(final Instant timestamp) {
    final SortedMap<Instant, UUID> headMap = autoaddIndex.headMap(timestamp);
    if (headMap.isEmpty()) return Optional.empty();
    return Optional.of(autoaddIndex.get(headMap.lastKey()));
  }

  @Value
  private static class AutoaddEntry {
    private Instant time;
    private UUID id;
  }
}
