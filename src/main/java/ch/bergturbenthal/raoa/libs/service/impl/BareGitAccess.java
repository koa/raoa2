package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.model.AlbumEntryKey;
import ch.bergturbenthal.raoa.libs.model.AlbumMeta;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.Updater;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.Cleanup;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.eclipse.jgit.dircache.*;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.ehcache.Cache;
import org.joda.time.format.ISODateTimeFormat;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.xml.sax.SAXException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.util.function.Tuples;

@Slf4j
@ToString
public class BareGitAccess implements GitAccess {
  private static final Duration REPOSITORY_CACHE_TIME = Duration.ofSeconds(20);

  private static ObjectWriter albumMetaWriter;
  private static com.fasterxml.jackson.databind.ObjectReader albumMetaRader;

  static {
    final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    albumMetaRader = objectMapper.readerFor(AlbumMeta.class);
    albumMetaWriter = objectMapper.writerFor(AlbumMeta.class);
  }

  private final Mono<Repository> repository;
  private final Mono<AlbumMeta> albumMetaSupplier;
  private final ConcurrencyLimiter limiter;
  Map<AlbumEntryKey, Mono<Metadata>> pendingMetdataLoad =
      Collections.synchronizedMap(new HashMap<>());
  private Scheduler ioScheduler;
  private Scheduler processScheduler;
  private Cache<AlbumEntryKey, Metadata> metadataCache;

  public BareGitAccess(
      final Path path,
      final Cache<AlbumEntryKey, Metadata> metadataCache,
      Scheduler ioScheduler,
      final Scheduler processScheduler,
      final ConcurrencyLimiter limiter) {
    this.limiter = limiter;
    this.metadataCache = metadataCache;

    repository =
        limiter
            .limit(
                Mono.<Repository>create(
                    sink -> {
                      try {
                        sink.success(
                            new FileRepositoryBuilder()
                                .setGitDir(path.toFile())
                                .readEnvironment()
                                .build());
                      } catch (IOException e1) {
                        sink.error(new RuntimeException("Cannot load repository " + path, e1));
                      }
                    }))
            .cache(REPOSITORY_CACHE_TIME);
    this.ioScheduler = ioScheduler;
    this.processScheduler = processScheduler;

    Consumer<AlbumMeta> metaUpdater =
        newMetadata -> {
          try {
            final File tempFile = File.createTempFile("metadata", ".json");
            albumMetaWriter.writeValue(tempFile, newMetadata);
            createUpdater()
                .flatMap(u -> u.importFile(tempFile.toPath(), ".raoa.json", true).map(l -> u))
                .flatMap(u -> u.commit("Metadata updated").map(l -> u))
                .doFinally(signal -> tempFile.delete())
                .subscribe(
                    updater -> updater.close(), ex -> log.warn("Cannot update metadata", ex));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        };
    albumMetaSupplier =
        limiter
            .limit(
                readObject(".raoa.json")
                    .flatMap(
                        l -> {
                          try {
                            return Mono.just(l.openStream())
                                .subscribeOn(ioScheduler)
                                .publishOn(processScheduler);
                          } catch (MissingObjectException e) {
                            return Mono.empty();
                          } catch (IOException e) {
                            return Mono.error(e);
                          }
                        })
                    .flatMap(
                        stream -> {
                          try (stream) {
                            return Mono.just(albumMetaRader.<AlbumMeta>readValue(stream))
                                .subscribeOn(ioScheduler);
                          } catch (IOException e) {
                            return Mono.error(e);
                          }
                        }))
            .publishOn(processScheduler)
            .map(
                data -> {
                  if (data.getAlbumId() == null) {
                    final AlbumMeta updatedMeta =
                        data.toBuilder().albumId(UUID.randomUUID()).build();
                    metaUpdater.accept(updatedMeta);
                    return updatedMeta;
                  } else {
                    return data;
                  }
                })
            .switchIfEmpty(
                createName()
                    .map(
                        name -> {
                          final UUID uuid = UUID.randomUUID();
                          final AlbumMeta albumMeta =
                              AlbumMeta.builder().albumId(uuid).albumTitle(name).build();
                          metaUpdater.accept(albumMeta);
                          return albumMeta;
                        }))
            .cache(Duration.ofMillis(100));
  }

  public static BareGitAccess accessOf(
      Path path,
      final Cache<AlbumEntryKey, Metadata> metadataCache,
      Scheduler ioScheduler,
      final Scheduler processScheduler,
      final ConcurrencyLimiter limiter) {
    return new BareGitAccess(path, metadataCache, ioScheduler, processScheduler, limiter);
  }

  @Override
  public Flux<GitFileEntry> listFiles(TreeFilter filter) {
    return Mono.zip(repository, masterTree())
        .flatMapMany(
            t -> {
              final Repository reps = t.getT1();
              final RevTree revTree = t.getT2();
              return Flux.<GitFileEntry>create(
                      sink -> {
                        Runnable run =
                            () -> {
                              Semaphore freeSlots = new Semaphore(1);
                              sink.onRequest(
                                  count -> {
                                    // log.info("Request: " + count);
                                    freeSlots.release(
                                        (int)
                                            Math.min(
                                                Integer.MAX_VALUE - freeSlots.availablePermits(),
                                                count));
                                    // log.info("free: " + freeSlots);
                                  });
                              AtomicBoolean done = new AtomicBoolean(false);
                              sink.onCancel(
                                  () -> {
                                    // log.info("Cancel");
                                    done.set(true);
                                  });
                              try {
                                try (ObjectReader objectReader = reps.newObjectReader()) {
                                  TreeWalk tw = new TreeWalk(reps, objectReader);
                                  tw.setFilter(filter);
                                  tw.reset(new ObjectId[] {revTree.getId()});
                                  tw.setRecursive(true);
                                  while (!done.get() && tw.next()) {
                                    // log.info("Free slots: " + freeSlots);
                                    final String nameString = tw.getNameString();
                                    final FileMode fileMode = tw.getFileMode();
                                    final ObjectId fileId = tw.getObjectId(0);
                                    if (!freeSlots.tryAcquire()) {
                                      //                                      log.info("Start
                                      // Throttle");
                                      freeSlots.acquire();
                                      //                                    log.info("End
                                      // Throttle");
                                    }
                                    sink.next(new GitFileEntry(nameString, fileMode, fileId));
                                  }
                                }
                              } catch (IOException | InterruptedException e) {
                                sink.error(e);
                              }
                              sink.complete();
                            };
                        new Thread(run, "list files").start();
                      })
                  // .log("list files")
                  .subscribeOn(ioScheduler)
                  .publishOn(processScheduler, 3);
            });
  }

  @Override
  public Mono<ObjectLoader> readObject(AnyObjectId objectId) {
    return repository
        .map(Repository::getObjectDatabase)
        .flatMap(
            db -> {
              try {
                return Mono.just(db.open(objectId)).subscribeOn(ioScheduler);
              } catch (MissingObjectException ex) {
                return Mono.empty();
              } catch (IOException e) {
                return Mono.error(e);
              }
            });
  }

  @Override
  public Mono<ObjectLoader> readObject(String filename) {
    return Mono.zip(repository, masterTree())
        .flatMap(
            t -> {
              try {
                final Repository rep = t.getT1();
                final RevTree tree = t.getT2();
                final TreeWalk treeWalk = TreeWalk.forPath(rep, filename, tree);
                if (treeWalk == null) return Mono.empty();
                final ObjectId objectId = treeWalk.getObjectId(0);
                final ObjectLoader objectLoader =
                    rep.getObjectDatabase().open(objectId, Constants.OBJ_BLOB);

                return Mono.just(objectLoader);
              } catch (MissingObjectException e) {
                return Mono.empty();
              } catch (IOException e) {
                return Mono.error(e);
              }
            })
        .subscribeOn(ioScheduler)
        .publishOn(processScheduler);
  }

  private Mono<RevTree> masterTree() {
    return findMasterRef().flatMap(this::readTree);
  }

  private Mono<RevTree> readTree(final Ref ref) {
    return repository
        .flatMap(
            r -> {
              try {
                return Mono.just(r.parseCommit(ref.getObjectId()));
              } catch (IOException e) {
                return Mono.error(e);
              }
            })
        .subscribeOn(ioScheduler)
        .publishOn(processScheduler)
        .map(RevCommit::getTree);
  }

  private Mono<Ref> findMasterRef() {
    return repository
        .map(Repository::getRefDatabase)
        .flatMap(
            db -> {
              try {
                return Mono.just(db.findRef("master"));
              } catch (IOException e) {
                return Mono.error(e);
              }
            })
        .subscribeOn(ioScheduler)
        .publishOn(processScheduler);
  }

  @Override
  public Flux<Instant> readAutoadd() {
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();

    return readObject(".autoadd")
        .map(ObjectLoader::getBytes)
        .subscribeOn(ioScheduler)
        .publishOn(processScheduler)
        .map(ByteBuffer::wrap)
        .map(
            b1 -> {
              try {
                return decoder.decode(b1);
              } catch (CharacterCodingException e) {
                throw new RuntimeException("Cannot read date string", e);
              }
            })
        .map(CharBuffer::toString)
        .flatMapIterable(s -> Arrays.asList(s.split("\n")))
        .map(String::trim)
        .map(line -> ISODateTimeFormat.dateTimeParser().parseDateTime(line).toDate().toInstant());
  }

  @Override
  public Mono<Updater> createUpdater() {

    return repository.flatMap(
        rep ->
            findMasterRef()
                .flatMap(r -> readTree(r).map(t -> Tuples.of(r, t)))
                .map(
                    t -> {
                      final Ref masterRef = t.getT1();
                      final RevTree tree = t.getT2();
                      Map<ObjectId, String> alreadyExistingFiles =
                          Collections.synchronizedMap(new HashMap<>());
                      final DirCache dirCache;
                      final DirCacheBuilder builder;
                      final boolean isBareRepository = rep.isBare();
                      final Function<ObjectReader, TreeWalk> treeWalkSupplier;
                      final Function<TreeWalk, DirCacheEntry> dirCacheEntryCreator;
                      Set<String> replacedFiles = Collections.synchronizedSet(new HashSet<>());
                      try {
                        if (isBareRepository) {
                          dirCache = DirCache.newInCore();

                          builder = dirCache.builder();
                          treeWalkSupplier =
                              (reader) -> {
                                try {
                                  TreeWalk tw = new TreeWalk(rep, reader);

                                  tw.reset(tree);
                                  tw.setRecursive(true);
                                  return tw;
                                } catch (IOException e) {
                                  throw new RuntimeException("Cannot create tree-walk", e);
                                }
                              };
                          dirCacheEntryCreator =
                              tw -> {
                                final DirCacheEntry dirCacheEntry =
                                    new DirCacheEntry(tw.getPathString());
                                dirCacheEntry.setObjectId(tw.getObjectId(0));
                                dirCacheEntry.setFileMode(tw.getFileMode());
                                return dirCacheEntry;
                              };

                        } else {
                          dirCache = rep.lockDirCache();

                          builder = dirCache.builder();
                          treeWalkSupplier =
                              reader -> {
                                TreeWalk tw = new TreeWalk(rep, reader);

                                tw.addTree(new DirCacheBuildIterator(builder));
                                tw.setRecursive(true);
                                return tw;
                              };
                          dirCacheEntryCreator =
                              tw -> {
                                final DirCacheEntry dirCacheEntry =
                                    tw.getTree(0, DirCacheIterator.class).getDirCacheEntry();
                                return dirCacheEntry;
                              };
                        }
                        try (ObjectReader reader = rep.newObjectReader()) {
                          TreeWalk tw = treeWalkSupplier.apply(reader);
                          while (tw.next()) {
                            alreadyExistingFiles.put(tw.getObjectId(0), tw.getPathString());
                          }
                        }
                      } catch (IOException e) {
                        throw new RuntimeException("Cannot prepare updater", e);
                      }
                      return new Updater() {
                        private boolean modified = false;

                        @Override
                        public void close() {
                          dirCache.unlock();
                        }

                        @Override
                        public Mono<Boolean> importFile(final Path file, final String name) {
                          return importFile(file, name, false);
                        }

                        @Override
                        public synchronized Mono<Boolean> importFile(
                            final Path file, final String name, boolean replaceIfExists) {
                          if (replaceIfExists) replacedFiles.add(name);
                          return Mono.<Boolean>create(
                                  booleanMonoSink -> {
                                    try (final ObjectInserter objectInserter =
                                        rep.newObjectInserter()) {
                                      final ObjectId newFileId =
                                          objectInserter.insert(
                                              Constants.OBJ_BLOB,
                                              Files.size(file),
                                              Files.newInputStream(file));
                                      final String existingFileName =
                                          alreadyExistingFiles.get(newFileId);
                                      if (existingFileName != null) {
                                        log.info(
                                            "File "
                                                + file
                                                + " already imported as "
                                                + existingFileName
                                                + " -> merging");
                                      } else {
                                        alreadyExistingFiles.put(newFileId, name);
                                        final DirCacheEntry newEntry = new DirCacheEntry(name);
                                        newEntry.setFileMode(FileMode.REGULAR_FILE);
                                        newEntry.setObjectId(newFileId);
                                        builder.add(newEntry);
                                        modified = true;
                                      }
                                      booleanMonoSink.success(Boolean.TRUE);
                                    } catch (IOException e) {
                                      booleanMonoSink.error(e);
                                    }
                                  })
                              .subscribeOn(ioScheduler)
                              .publishOn(processScheduler);
                        }

                        @Override
                        public Mono<Boolean> commit() {
                          return commit(null);
                        }

                        @Override
                        public Mono<Boolean> commit(String message) {

                          return findMasterRef()
                              .map(
                                  currentMasterRef -> {
                                    try {
                                      if (!modified) {
                                        return false;
                                      }
                                      try (ObjectReader reader = rep.newObjectReader()) {
                                        TreeWalk tw = treeWalkSupplier.apply(reader);
                                        while (tw.next()) {
                                          if (!replacedFiles.contains(tw.getPathString()))
                                            builder.add(dirCacheEntryCreator.apply(tw));
                                        }
                                      }
                                      builder.finish();
                                      if (!isBareRepository) {
                                        // update index
                                        dirCache.write();
                                        if (!dirCache.commit()) return false;
                                      }
                                    } catch (IOException e) {
                                      log.warn("Cannot prepare commit", e);
                                      return false;
                                    }
                                    try (final ObjectInserter objectInserter =
                                        rep.newObjectInserter()) {

                                      final ObjectId treeId = dirCache.writeTree(objectInserter);
                                      final CommitBuilder commit = new CommitBuilder();
                                      final PersonIdent author =
                                          new PersonIdent("raoa-importer", "photos@teamkoenig.ch");
                                      if (message != null) commit.setMessage(message);
                                      commit.setAuthor(author);
                                      commit.setCommitter(author);
                                      commit.setParentIds(currentMasterRef.getObjectId());
                                      commit.setTreeId(treeId);
                                      final ObjectId commitId = objectInserter.insert(commit);

                                      objectInserter.flush();

                                      RevCommit revCommit = rep.parseCommit(commitId);
                                      RefUpdate ru = rep.updateRef(Constants.HEAD);
                                      ru.setNewObjectId(commitId);
                                      ru.setRefLogMessage(
                                          "auto import" + revCommit.getShortMessage(), false);
                                      ru.setExpectedOldObjectId(masterRef.getObjectId());
                                      switch (ru.update()) {
                                        case NOT_ATTEMPTED:
                                        case LOCK_FAILURE:
                                        case REJECTED_OTHER_REASON:
                                        case REJECTED_MISSING_OBJECT:
                                        case IO_FAILURE:
                                        case REJECTED_CURRENT_BRANCH:
                                        case REJECTED:
                                          return false;
                                        case NO_CHANGE:
                                        case FAST_FORWARD:
                                        case FORCED:
                                        case NEW:
                                        case RENAMED:
                                          if (!isBareRepository)
                                            // checkout index
                                            try (ObjectReader reader = rep.newObjectReader()) {
                                              final File workTree = rep.getWorkTree();
                                              for (int i = 0; i < dirCache.getEntryCount(); i++) {
                                                final DirCacheEntry dirCacheEntry =
                                                    dirCache.getEntry(i);
                                                if (dirCacheEntry == null) continue;
                                                final File file =
                                                    new File(
                                                        workTree, dirCacheEntry.getPathString());
                                                if (replacedFiles.contains(
                                                    dirCacheEntry.getPathString())) file.delete();
                                                if (file.exists()) continue;
                                                @Cleanup
                                                final FileOutputStream outputStream =
                                                    new FileOutputStream(file);
                                                reader
                                                    .open(dirCacheEntry.getObjectId())
                                                    .copyTo(outputStream);
                                              }
                                            }
                                          log.info("Commit successful " + rep.getDirectory());
                                          return true;
                                      }

                                      return false;
                                    } catch (IOException e) {
                                      log.warn("Cannot finish commit", e);
                                      return false;
                                    }
                                  })
                              .defaultIfEmpty(Boolean.FALSE)
                              .doFinally(
                                  signal -> {
                                    if (!isBareRepository) dirCache.unlock();
                                  })
                              .subscribeOn(ioScheduler);
                        }
                      };
                    }));
  }

  @Override
  public Mono<String> getName() {
    return getMetadata().map(AlbumMeta::getAlbumTitle);
  }

  private Mono<String> createName() {
    return repository.map(
        rep -> {
          if (rep.isBare()) {
            final String filename = rep.getDirectory().getName();
            return filename.substring(0, filename.length() - 4);
          } else {
            return rep.getWorkTree().getName();
          }
        });
  }

  @Override
  public Mono<AlbumMeta> getMetadata() {
    return albumMetaSupplier;
  }

  @Override
  public Mono<Metadata> entryMetdata(final AnyObjectId entryId) {
    return getMetadata()
        .flatMap(
            albumMeta -> {
              final AlbumEntryKey cacheKey =
                  new AlbumEntryKey(albumMeta.getAlbumId(), entryId.toObjectId());
              final Metadata cachedValue = metadataCache.get(cacheKey);
              if (cachedValue != null) return Mono.just(cachedValue);

              return pendingMetdataLoad
                  .computeIfAbsent(
                      cacheKey,
                      k ->
                          limiter.limit(
                              readObject(entryId)
                                  .map(
                                      loader -> {
                                        try {
                                          final File tempFile =
                                              File.createTempFile(entryId.name(), ".tmp");
                                          try {
                                            try (final FileOutputStream outputStream =
                                                new FileOutputStream(tempFile)) {
                                              loader.copyTo(outputStream);
                                            }
                                            AutoDetectParser parser = new AutoDetectParser();
                                            BodyContentHandler handler = new BodyContentHandler();
                                            Metadata metadata = new Metadata();
                                            final TikaInputStream inputStream =
                                                TikaInputStream.get(tempFile);
                                            parser.parse(inputStream, handler, metadata);
                                            metadataCache.put(k, metadata);
                                            return metadata;
                                          } finally {
                                            tempFile.delete();
                                          }
                                        } catch (IOException | SAXException | TikaException e) {
                                          throw new RuntimeException(
                                              "Cannot read metadata of " + entryId, e);
                                        }
                                      })
                                  .subscribeOn(ioScheduler)
                                  .cache()
                                  .publishOn(processScheduler)))
                  .doFinally(signal -> pendingMetdataLoad.remove(cacheKey));
            });
  }
}
