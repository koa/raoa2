package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.model.AlbumMeta;
import ch.bergturbenthal.raoa.libs.service.AsyncService;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.Updater;
import com.adobe.xmp.XMPMeta;
import com.adobe.xmp.XMPMetaFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
import org.jetbrains.annotations.NotNull;
import org.joda.time.format.ISODateTimeFormat;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.xml.sax.SAXException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.function.TupleUtils;
import reactor.util.function.Tuples;

@Slf4j
@ToString
public class BareGitAccess implements GitAccess {
  public static final String METADATA_FILENAME = ".raoa.json";
  private static final Duration REPOSITORY_CACHE_TIME = Duration.ofSeconds(20);
  private static final ObjectWriter albumMetaWriter;
  private static final com.fasterxml.jackson.databind.ObjectReader albumMetaRader;

  static {
    final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    albumMetaRader = objectMapper.readerFor(AlbumMeta.class);
    albumMetaWriter = objectMapper.writerFor(AlbumMeta.class);
  }

  private final Mono<Repository> repository;
  private final Mono<AlbumMeta> albumMetaSupplier;
  private final MeterRegistry meterRegistry;
  private final Path relativePath;
  private final AsyncService asyncService;
  private final Scheduler processScheduler;
  private final AtomicReference<Mono<Ref>> cachedMasterRef = new AtomicReference<>();
  private final AtomicReference<Mono<RevTree>> cachedMasterTree = new AtomicReference<>();

  private BareGitAccess(
      final Path path,
      final Path relativePath,
      final AsyncService asyncService,
      final Scheduler processScheduler,
      final MeterRegistry meterRegistry) {
    this.relativePath = relativePath;
    this.asyncService = asyncService;
    this.processScheduler = processScheduler;
    this.meterRegistry = meterRegistry;

    repository =
        this.asyncService
            .asyncMono(
                () ->
                    new FileRepositoryBuilder().setGitDir(path.toFile()).readEnvironment().build())
            .cache(REPOSITORY_CACHE_TIME);

    Consumer<AlbumMeta> metaUpdater =
        newMetadata ->
            writeNewMetadata(newMetadata)
                .subscribe(Updater::close, ex -> log.warn("Cannot update metadata", ex));

    albumMetaSupplier =
        readObject(METADATA_FILENAME)
            .flatMap(
                l ->
                    createAsyncMonoOptional(
                        () -> {
                          try (final ObjectStream src = l.openStream()) {
                            return Optional.of(albumMetaRader.<AlbumMeta>readValue(src));
                          } catch (MissingObjectException e) {
                            return Optional.empty();
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
      final Path relativePath,
      AsyncService ioScheduler,
      final Scheduler processScheduler,
      MeterRegistry meterRegistry) {
    return new BareGitAccess(path, relativePath, ioScheduler, processScheduler, meterRegistry);
  }

  @NotNull
  private Mono<Updater> writeNewMetadata(final AlbumMeta newMetadata) {
    return asyncService
        .asyncMono(
            () -> {
              final File newFile = File.createTempFile("metadata", ".json");
              albumMetaWriter.writeValue(newFile, newMetadata);
              return newFile;
            })
        .flatMap(
            tempFile ->
                createUpdater()
                    .flatMap(u -> u.importFile(tempFile.toPath(), ".raoa.json", true).map(l -> u))
                    .flatMap(u -> u.commit("Metadata updated").map(l -> u))
                    .doFinally(signal -> tempFile.delete()));
  }

  @Override
  public Flux<GitFileEntry> listFiles(TreeFilter filter) {
    return Mono.zip(repository, masterTree())
        .flatMapMany(
            t -> {
              final Repository reps = t.getT1();
              final RevTree revTree = t.getT2();
              return Flux.create(
                  sink -> {
                    Semaphore freeSlots = new Semaphore(0);
                    AtomicBoolean started = new AtomicBoolean(false);
                    sink.onRequest(
                        count -> {
                          // log.info("Request: " + count);
                          freeSlots.release(
                              (int)
                                  Math.min(
                                      Integer.MAX_VALUE - freeSlots.availablePermits(), count));
                          // log.info("free: " + freeSlots);
                          if (started.compareAndSet(false, true)) {
                            new Thread(
                                    () -> {
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
                                            final String nameString = tw.getPathString();
                                            final FileMode fileMode = tw.getFileMode();
                                            final ObjectId fileId = tw.getObjectId(0);
                                            if (!freeSlots.tryAcquire()) {
                                              //
                                              // log.info("Start
                                              // Throttle");
                                              freeSlots.acquire();
                                              //
                                              // log.info("End
                                              // Throttle");
                                            }
                                            sink.next(
                                                new GitFileEntry(nameString, fileMode, fileId));
                                          }
                                        }
                                      } catch (IOException | InterruptedException e) {
                                        sink.error(e);
                                      }
                                      sink.complete();
                                    },
                                    "list files")
                                .start();
                          }
                        });
                  })
              // .log("list files")
              // .publishOn(processScheduler, 3)
              ;
            });
  }

  @Override
  public Mono<ObjectLoader> readObject(AnyObjectId objectId) {

    return repository
        .map(Repository::getObjectDatabase)
        .flatMap(
            db ->
                this.createAsyncMonoOptional(
                    () -> {
                      try {
                        return Optional.of(db.open(objectId));
                      } catch (MissingObjectException ex) {
                        return Optional.empty();
                      }
                    }));
    // .log("read " + objectId);
  }

  @Override
  public Mono<String> filenameOfObject(AnyObjectId objectId) {
    return Mono.zip(repository, masterTree())
        .flatMap(
            TupleUtils.function(
                (rep, tree) ->
                    asyncService.asyncMonoOptional(
                        () -> {
                          final TreeWalk tw = new TreeWalk(rep);

                          tw.setFilter(
                              new TreeFilter() {
                                @Override
                                public boolean include(final TreeWalk walker) {
                                  return walker.getObjectId(0).equals(objectId);
                                }

                                @Override
                                public boolean shouldBeRecursive() {
                                  return true;
                                }

                                @Override
                                public TreeFilter clone() {
                                  return this;
                                }
                              });
                          tw.reset(tree);
                          tw.setRecursive(false);
                          if (tw.next()) {
                            return Optional.of(tw.getPathString());
                          } else return Optional.empty();
                        })));
  }

  private <T> Mono<T> createAsyncMonoOptional(Callable<Optional<T>> callable) {
    return asyncService.asyncMono(callable).filter(Optional::isPresent).map(Optional::get);
  }

  @Override
  public Mono<ObjectLoader> readObject(String filename) {
    return Mono.zip(repository, masterTree())
        .flatMap(
            t ->
                this.createAsyncMonoOptional(
                    () -> {
                      try {
                        final Repository rep = t.getT1();
                        final RevTree tree = t.getT2();
                        final TreeWalk treeWalk = TreeWalk.forPath(rep, filename, tree);
                        if (treeWalk == null) {
                          return Optional.empty();
                        }
                        final ObjectId objectId = treeWalk.getObjectId(0);
                        final ObjectLoader objectLoader =
                            rep.getObjectDatabase().open(objectId, Constants.OBJ_BLOB);
                        return Optional.of(objectLoader);
                      } catch (MissingObjectException e) {
                        return Optional.empty();
                      }
                    }))
        // .log("read " + filename)
        .publishOn(processScheduler);
  }

  private Mono<RevTree> masterTree() {
    return cachedMasterTree.updateAndGet(
        treeMono ->
            Objects.requireNonNullElseGet(
                treeMono,
                () -> findMasterRef().flatMap(this::readTree).cache(REPOSITORY_CACHE_TIME)));
  }

  private Mono<RevTree> readTree(final Ref ref) {
    // log.info("Read tree " + ref + " at " + relativePath);
    return repository
        .flatMap(r -> asyncService.asyncMono(() -> r.parseCommit(ref.getObjectId())))
        .publishOn(processScheduler)
        .map(RevCommit::getTree);
  }

  @Override
  public Mono<ObjectId> getCurrentVersion() {
    return findMasterRef().map(Ref::getObjectId);
  }

  private Mono<Ref> findMasterRef() {
    return cachedMasterRef
        .updateAndGet(
            refMono ->
                Objects.requireNonNullElseGet(
                    refMono,
                    () ->
                        repository
                            .map(Repository::getRefDatabase)
                            .flatMap(db -> asyncService.asyncMono(() -> db.findRef("master")))
                            .cache(REPOSITORY_CACHE_TIME)))
        .publishOn(processScheduler);
  }

  @Override
  public Flux<Instant> readAutoadd() {
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
    return readObject(".autoadd")
        .map(ObjectLoader::getBytes)
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
                .flatMap(
                    t -> asyncService.asyncMono(() -> createUpdater(rep, t.getT1(), t.getT2()))));
  }

  private Updater createUpdater(final Repository rep, final Ref masterRef, final RevTree tree) {
    Map<ObjectId, String> alreadyExistingFiles = Collections.synchronizedMap(new HashMap<>());
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
              final DirCacheEntry dirCacheEntry = new DirCacheEntry(tw.getPathString());
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
        dirCacheEntryCreator = tw -> tw.getTree(0, DirCacheIterator.class).getDirCacheEntry();
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
        if (!isBareRepository) dirCache.unlock();
      }

      @Override
      public Mono<Boolean> importFile(final Path file, final String name) {
        return importFile(file, name, false);
      }

      @Override
      public Mono<Boolean> importFile(final Path file, final String name, boolean replaceIfExists) {
        if (replaceIfExists) replacedFiles.add(name);
        return asyncService.asyncMono(
            () -> {
              try (final ObjectInserter objectInserter = rep.newObjectInserter()) {
                final ObjectId newFileId =
                    objectInserter.insert(
                        Constants.OBJ_BLOB, Files.size(file), Files.newInputStream(file));
                synchronized (alreadyExistingFiles) {
                  final String existingFileName = alreadyExistingFiles.get(newFileId);
                  if (existingFileName != null && existingFileName.equals(name)) {
                    log.info(
                        "File "
                            + file
                            + " already imported as "
                            + existingFileName
                            + " -> skipping");
                    return false;
                  }
                  alreadyExistingFiles.put(newFileId, name);
                  final DirCacheEntry newEntry = new DirCacheEntry(name);
                  newEntry.setFileMode(FileMode.REGULAR_FILE);
                  newEntry.setObjectId(newFileId);
                  builder.add(newEntry);
                  modified = true;
                }
                return true;
              }
            });
      }

      @Override
      public Mono<Boolean> removeFile(final String name) {
        replacedFiles.add(name);
        synchronized (alreadyExistingFiles) {
          final boolean wasExisting = alreadyExistingFiles.containsValue(name);
          modified = true;
          return Mono.just(wasExisting);
        }
      }

      @Override
      public Mono<Boolean> commit() {
        return commit(null);
      }

      @Override
      public Mono<Boolean> commit(String message) {
        final Mono<String> nameMono = getName();
        return Mono.zip(findMasterRef(), nameMono)
            .flatMap(
                t -> executeCommit(message, t.getT1())
                // .log("Commit " + t.getT2())
                )
            .defaultIfEmpty(Boolean.FALSE)
            .doFinally(
                signal -> {
                  if (!isBareRepository) dirCache.unlock();
                });
      }

      private Mono<Boolean> executeCommit(final String message, final Ref currentMasterRef) {
        if (!modified) {
          return Mono.just(true);
        }
        return asyncService.asyncMono(
            () -> {
              try {

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
                  if (!dirCache.commit()) {
                    return (false);
                  }
                }
              } catch (IOException e) {
                log.warn("Cannot prepare commit", e);
                return false;
              }
              try (final ObjectInserter objectInserter = rep.newObjectInserter()) {

                final ObjectId treeId = dirCache.writeTree(objectInserter);
                final CommitBuilder commit = new CommitBuilder();
                final PersonIdent author = new PersonIdent("raoa-importer", "photos@teamkoenig.ch");
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
                ru.setRefLogMessage("auto import" + revCommit.getShortMessage(), false);
                ru.setExpectedOldObjectId(masterRef.getObjectId());
                final RefUpdate.Result updateResult = ru.update();
                switch (updateResult) {
                  case LOCK_FAILURE:
                    throw new IllegalStateException("Lock Failure");
                  case NOT_ATTEMPTED:
                  case REJECTED_OTHER_REASON:
                  case REJECTED_MISSING_OBJECT:
                  case IO_FAILURE:
                  case REJECTED_CURRENT_BRANCH:
                  case REJECTED:
                    log.warn("Error committing: " + updateResult);
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
                          final DirCacheEntry dirCacheEntry = dirCache.getEntry(i);
                          if (dirCacheEntry == null) continue;
                          final File file = new File(workTree, dirCacheEntry.getPathString());
                          if (replacedFiles.contains(dirCacheEntry.getPathString())) file.delete();
                          if (file.exists()) continue;
                          @Cleanup final FileOutputStream outputStream = new FileOutputStream(file);
                          reader.open(dirCacheEntry.getObjectId()).copyTo(outputStream);
                        }
                      }
                    log.info("Commit successful " + rep.getDirectory());
                    cachedMasterRef.set(null);
                    cachedMasterTree.set(null);
                    return true;
                }
              } catch (IOException e) {
                log.warn("Cannot finish commit", e);
              }
              log.warn("Commit not executed");
              return false;
            });
      }
    };
  }

  @Override
  public Mono<String> getName() {
    return getMetadata().map(AlbumMeta::getAlbumTitle);
  }

  @Override
  public Mono<String> getFullPath() {
    final String pathString = relativePath.toString();
    return repository.map(
        r -> {
          if (r.isBare() && pathString.endsWith(".git"))
            return pathString.substring(0, pathString.length() - 4);
          else return pathString;
        });
  }

  public String toString() {
    return "BareGitAccess[" + relativePath.getFileName().toString() + "]";
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
  public Mono<Boolean> updateMetadata(final Function<AlbumMeta, AlbumMeta> mutation) {
    return albumMetaSupplier
        .map(mutation)
        .flatMap(this::writeNewMetadata)
        .map(u -> true)
        .defaultIfEmpty(false);
  }

  @Override
  public Mono<Metadata> entryMetdata(final AnyObjectId entryId) {
    return getMetadata()
        .flatMap(
            albumMeta ->
                readObject(entryId)
                    .flatMap(
                        loader ->
                            asyncService.asyncMono(
                                () ->
                                    meterRegistry
                                        .timer("git-access.metadata.load")
                                        .record(
                                            () -> {
                                              try (final ObjectStream stream =
                                                  loader.openStream()) {
                                                AutoDetectParser parser = new AutoDetectParser();
                                                BodyContentHandler handler =
                                                    new BodyContentHandler();
                                                Metadata metadata = new Metadata();
                                                @Cleanup
                                                final TikaInputStream inputStream =
                                                    TikaInputStream.get(stream);
                                                parser.parse(inputStream, handler, metadata);
                                                return metadata;
                                              } catch (IOException
                                                  | SAXException
                                                  | TikaException e) {
                                                throw new RuntimeException(
                                                    "Cannot read metadata of " + entryId, e);
                                              }
                                            })))
                    .cache());
  }

  @Override
  public Mono<XMPMeta> readXmpMeta(final ObjectLoader loader) {
    return asyncService.asyncMono(
        () -> {
          try (final ObjectStream stream = loader.openStream()) {
            return (XMPMetaFactory.parse(stream));
          }
        });
  }

  @Override
  public Mono<Boolean> writeXmpMeta(final String filename, final XMPMeta xmpMeta) {
    return createUpdater()
        .flatMap(
            updater ->
                asyncService
                    .asyncMono(
                        () -> {
                          final File file = File.createTempFile("data", ".xmp");
                          final OutputStream fos = new FileOutputStream(file);
                          XMPMetaFactory.serialize(xmpMeta, fos);
                          fos.close();
                          return updater
                              .importFile(file.toPath(), filename, true)
                              .doFinally(signal -> file.delete());
                        })
                    .filterWhen(Function.identity())
                    .flatMap(ok -> updater.commit())
                    .filter(ok -> ok));
  }
}
