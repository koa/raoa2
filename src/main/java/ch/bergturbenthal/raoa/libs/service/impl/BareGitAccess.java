package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.model.AlbumMeta;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.Updater;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.Cleanup;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.dircache.*;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.joda.time.format.ISODateTimeFormat;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

@Slf4j
@ToString
public class BareGitAccess implements GitAccess {

  static {
    final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    albumMetaRader = objectMapper.readerFor(AlbumMeta.class);
    albumMetaWriter = objectMapper.writerFor(AlbumMeta.class);
  }

  private final Supplier<Repository> repository;
  private final Supplier<AlbumMeta> albumMetaSupplier;
  private static ObjectWriter albumMetaWriter;
  private static com.fasterxml.jackson.databind.ObjectReader albumMetaRader;

  public BareGitAccess(final Path path) {
    final AtomicReference<WeakReference<Repository>> loadedRepository = new AtomicReference<>();
    final Object repositoryLock = new Object();
    repository =
        () -> {
          synchronized (repositoryLock) {
            final WeakReference<Repository> repositoryWeakReference = loadedRepository.get();
            if (repositoryWeakReference != null) {
              final Repository cachedRepository = repositoryWeakReference.get();
              if (cachedRepository != null) return cachedRepository;
            }
            try {
              Repository newRepository =
                  new FileRepositoryBuilder().setGitDir(path.toFile()).readEnvironment().build();
              loadedRepository.set(new WeakReference<>(newRepository));
              return newRepository;
            } catch (IOException e) {
              throw new RuntimeException("Cannot load repository " + path, e);
            }
          }
        };
    Consumer<AlbumMeta> metaUpdater =
        newMetadata -> {
          try {

            final File tempFile = File.createTempFile("metadata", ".json");
            albumMetaWriter.writeValue(tempFile, newMetadata);
            final Updater updater = createUpdater();
            updater.importFile(tempFile.toPath(), ".raoa.json");
            updater.commit("Metadata updated");
            tempFile.delete();
          } catch (IOException e) {
            throw new RuntimeException("Cannot update metdata", e);
          }
        };
    final AtomicReference<AlbumMeta> currentAlbumMeta = new AtomicReference<>();
    albumMetaSupplier =
        () -> {
          final AlbumMeta cachedValue = currentAlbumMeta.get();
          if (cachedValue != null) return cachedValue;
          synchronized (currentAlbumMeta) {
            final AlbumMeta cachedValue2 = currentAlbumMeta.get();
            if (cachedValue2 != null) return cachedValue2;
            try {
              final Optional<ObjectLoader> optionalObjectLoader = readObject(".raoa.json");
              if (optionalObjectLoader.isPresent()) {
                final ObjectLoader objectLoader = optionalObjectLoader.orElseThrow();
                @Cleanup final ObjectStream stream = objectLoader.openStream();
                final AlbumMeta data = albumMetaRader.readValue(stream);
                if (data.getAlbumId() == null) {
                  final AlbumMeta updatedMeta = data.toBuilder().albumId(UUID.randomUUID()).build();

                  currentAlbumMeta.set(updatedMeta);
                  return updatedMeta;
                } else {
                  currentAlbumMeta.set(data);
                  return data;
                }
              } else {
                final String name = createName();
                final UUID uuid = UUID.randomUUID();
                final AlbumMeta albumMeta =
                    AlbumMeta.builder().albumId(uuid).albumTitle(name).build();
                currentAlbumMeta.set(albumMeta);
                return albumMeta;
              }
            } catch (IOException e) {
              throw new RuntimeException("Cannot load metadata");
            }
          }
        };
  }

  public static BareGitAccess accessOf(Path path) throws IOException {
    return new BareGitAccess(path);
  }

  @Override
  public Collection<GitFileEntry> listFiles(TreeFilter filter) throws IOException {
    final Optional<RevTree> revTree = masterTree();
    if (revTree.isEmpty()) return Collections.emptyList();
    try (ObjectReader objectReader = repository.get().newObjectReader()) {
      TreeWalk tw = new TreeWalk(repository.get(), objectReader);
      tw.setFilter(filter);
      tw.reset(new ObjectId[] {revTree.get().getId()});
      tw.setRecursive(true);
      List<GitFileEntry> entries = new ArrayList<>();
      while (tw.next()) {
        final String nameString = tw.getNameString();
        final FileMode fileMode = tw.getFileMode();
        final ObjectId fileId = tw.getObjectId(0);
        entries.add(new GitFileEntry(nameString, fileMode, fileId));
      }
      return entries;
    }
  }

  @Override
  public Optional<ObjectLoader> readObject(AnyObjectId objectId) throws IOException {
    try {
      return Optional.of(repository.get().getObjectDatabase().open(objectId));
    } catch (MissingObjectException ex) {
      return Optional.empty();
    }
  }

  @Override
  public Optional<ObjectLoader> readObject(String filename) throws IOException {

    final Optional<RevTree> revTree = masterTree();
    if (revTree.isPresent()) {
      final RevTree tree = revTree.get();
      final TreeWalk treeWalk = TreeWalk.forPath(repository.get(), filename, tree);
      if (treeWalk == null) return Optional.empty();
      final ObjectId objectId = treeWalk.getObjectId(0);
      final ObjectLoader objectLoader =
          repository.get().getObjectDatabase().open(objectId, Constants.OBJ_BLOB);

      return Optional.of(objectLoader);

    } else return Optional.empty();
  }

  private Optional<RevTree> masterTree() throws IOException {
    final Optional<Ref> masterRef = findMasterRef();
    if (masterRef.isEmpty()) return Optional.empty();
    return Optional.of(readTree(masterRef.get()));
  }

  private RevTree readTree(final Ref ref) throws IOException {
    final RevCommit revCommit = repository.get().parseCommit(ref.getObjectId());
    return revCommit.getTree();
  }

  private Optional<Ref> findMasterRef() throws IOException {
    final RefDatabase refDatabase = repository.get().getRefDatabase();
    return Optional.ofNullable(refDatabase.findRef("master"));
  }

  @Override
  public Stream<Instant> readAutoadd() {
    try {
      CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();

      return readObject(".autoadd").map(ObjectLoader::getBytes).map(ByteBuffer::wrap)
          .map(
              b1 -> {
                try {
                  return decoder.decode(b1);
                } catch (CharacterCodingException e) {
                  throw new RuntimeException("Cannot read date string", e);
                }
              })
          .map(CharBuffer::toString).stream()
          .flatMap(s -> Stream.of(s.split("\n")))
          .map(String::trim)
          .map(line -> ISODateTimeFormat.dateTimeParser().parseDateTime(line).toDate().toInstant());
    } catch (Exception e) {
      log.error("Cannot load autoadd of " + repository, e);
    }
    return Stream.empty();
  }

  @Override
  public Updater createUpdater() {
    try {
      final DirCache dirCache;
      final DirCacheBuilder builder;
      final Optional<Ref> masterRef = findMasterRef();
      if (masterRef.isEmpty()) {
        log.warn("No Master found at " + repository);
        return new Updater() {
          @Override
          public boolean importFile(final Path file, final String name) throws IOException {
            return false;
          }

          @Override
          public boolean commit() {
            return false;
          }

          @Override
          public boolean commit(final String message) {
            return false;
          }
        };
      }
      Map<ObjectId, String> alreadyExistingFiles = new HashMap<>();
      if (repository.get().isBare()) {
        final RevTree tree = readTree(masterRef.get());
        dirCache = DirCache.newInCore();

        builder = dirCache.builder();

        try (ObjectReader reader = repository.get().newObjectReader()) {

          TreeWalk tw = new TreeWalk(repository.get(), reader);

          tw.reset(tree);
          tw.setRecursive(true);

          while (tw.next()) {
            final String pathString = tw.getPathString();
            final ObjectId objectId = tw.getObjectId(0);
            alreadyExistingFiles.put(objectId, pathString);
            final DirCacheEntry dirCacheEntry = new DirCacheEntry(pathString);
            dirCacheEntry.setObjectId(objectId);
            dirCacheEntry.setFileMode(tw.getFileMode());
            builder.add(dirCacheEntry);
          }
        }
      } else {
        dirCache = repository.get().lockDirCache();

        builder = dirCache.builder();

        try (ObjectReader reader = repository.get().newObjectReader()) {

          TreeWalk tw = new TreeWalk(repository.get(), reader);

          tw.addTree(new DirCacheBuildIterator(builder));
          tw.setRecursive(true);

          while (tw.next()) {
            final String pathString = tw.getPathString();
            final ObjectId objectId = tw.getObjectId(0);
            alreadyExistingFiles.put(objectId, pathString);
            builder.add(tw.getTree(0, DirCacheIterator.class).getDirCacheEntry());
          }
        }
      }

      return new Updater() {
        private boolean modified = false;

        @Override
        public synchronized boolean importFile(final Path file, final String name)
            throws IOException {
          try (final ObjectInserter objectInserter = repository.get().newObjectInserter()) {
            final ObjectId newFileId =
                objectInserter.insert(
                    Constants.OBJ_BLOB, Files.size(file), Files.newInputStream(file));
            final String existingFileName = alreadyExistingFiles.get(newFileId);
            if (existingFileName != null) {
              log.info("File " + file + " already imported as " + existingFileName + " -> merging");
              return true;
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

        @Override
        public boolean commit() {
          return commit(null);
        }

        @Override
        public synchronized boolean commit(String message) {
          try {
            if (!modified) {
              return true;
            }
            builder.finish();
            try (final ObjectInserter objectInserter = repository.get().newObjectInserter()) {
              if (!repository.get().isBare()) {
                // update index
                dirCache.write();
                if (!dirCache.commit()) return false;
              }

              final Optional<Ref> currentMasterRef = findMasterRef();
              if (currentMasterRef.isEmpty()) return false;

              final ObjectId treeId = dirCache.writeTree(objectInserter);
              final CommitBuilder commit = new CommitBuilder();
              final PersonIdent author = new PersonIdent("raoa-importer", "photos@teamkoenig.ch");
              if (message != null) commit.setMessage(message);
              commit.setAuthor(author);
              commit.setCommitter(author);
              commit.setParentIds(currentMasterRef.get().getObjectId());
              commit.setTreeId(treeId);
              final ObjectId commitId = objectInserter.insert(commit);

              objectInserter.flush();

              RevCommit revCommit = repository.get().parseCommit(commitId);
              RefUpdate ru = repository.get().updateRef(Constants.HEAD);
              ru.setNewObjectId(commitId);
              ru.setRefLogMessage("auto import" + revCommit.getShortMessage(), false);
              ru.setExpectedOldObjectId(masterRef.get().getObjectId());
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
                  if (!repository.get().isBare())
                    // checkout index
                    try (ObjectReader reader = repository.get().newObjectReader()) {
                      final File workTree = repository.get().getWorkTree();
                      for (int i = 0; i < dirCache.getEntryCount(); i++) {
                        final DirCacheEntry dirCacheEntry = dirCache.getEntry(i);
                        if (dirCacheEntry == null) continue;
                        final File file = new File(workTree, dirCacheEntry.getPathString());
                        if (file.exists()) continue;
                        @Cleanup final FileOutputStream outputStream = new FileOutputStream(file);
                        reader.open(dirCacheEntry.getObjectId()).copyTo(outputStream);
                      }
                    }
                  log.info("Commit successful " + repository.get().getDirectory());
                  return true;
              }

              return false;
            } catch (IOException e) {
              log.warn("Cannot finish commit", e);
              return false;
            }
          } finally {
            if (!repository.get().isBare()) dirCache.unlock();
          }
        }
      };

    } catch (IOException ex) {
      throw new RuntimeException("Cannot read master branch", ex);
    }
  }

  @Override
  public String getName() {
    return getMetadata().getAlbumTitle();
  }

  private String createName() {
    if (repository.get().isBare()) {
      final String filename = repository.get().getDirectory().getName();
      return filename.substring(0, filename.length() - 4);
    } else {
      return repository.get().getWorkTree().getName();
    }
  }

  @Override
  public AlbumMeta getMetadata() {
    return albumMetaSupplier.get();
  }
}
