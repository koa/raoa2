package ch.bergturbenthal.raoa.importer.domain.service.impl;

import ch.bergturbenthal.raoa.importer.domain.service.GitAccess;
import ch.bergturbenthal.raoa.importer.domain.service.Updater;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.ToString;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.joda.time.format.ISODateTimeFormat;

@Slf4j
@ToString
public class BareGitAccess implements GitAccess {

  private final Repository repository;

  public BareGitAccess(final Path path) throws IOException {
    repository = new FileRepositoryBuilder().setGitDir(path.toFile()).readEnvironment().build();
  }

  public static BareGitAccess accessOf(Path path) throws IOException {
    return new BareGitAccess(path);
  }

  @Override
  public Optional<ObjectLoader> readFile(String filename) throws IOException {

    final Optional<RevTree> revTree = masterTree();
    if (revTree.isPresent()) {
      final RevTree tree = revTree.get();
      final TreeWalk treeWalk = TreeWalk.forPath(repository, filename, tree);
      if (treeWalk == null) return Optional.empty();
      final ObjectId objectId = treeWalk.getObjectId(0);
      final ObjectLoader objectLoader =
          repository.getObjectDatabase().open(objectId, Constants.OBJ_BLOB);

      return Optional.of(objectLoader);

    } else return Optional.empty();
  }

  private Optional<RevTree> masterTree() throws IOException {
    final Optional<Ref> masterRef = findMasterRef();
    if (masterRef.isEmpty()) return Optional.empty();
    return Optional.of(readTree(masterRef.get()));
  }

  private RevTree readTree(final Ref ref) throws IOException {
    final RevCommit revCommit = repository.parseCommit(ref.getObjectId());
    return revCommit.getTree();
  }

  private Optional<Ref> findMasterRef() throws IOException {
    final RefDatabase refDatabase = repository.getRefDatabase();
    return Optional.ofNullable(refDatabase.findRef("master"));
  }

  @Override
  public Stream<Instant> readAutoadd() {
    try {
      CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();

      return readFile(".autoadd").map(ObjectLoader::getBytes).map(ByteBuffer::wrap)
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
      final Map<String, FileEntry> commitEntries = new HashMap<>();
      final Optional<Ref> masterRef = findMasterRef();
      final RevTree tree = readTree(masterRef.get());
      Map<ObjectId, String> alreadyExistingFiles = new HashMap<>();
      try (ObjectReader reader = repository.newObjectReader()) {
        TreeWalk tw = new TreeWalk(repository, reader);
        tw.reset(tree);
        tw.setRecursive(true);

        while (tw.next()) {
          final FileMode fileMode = tw.getFileMode();
          final String pathString = tw.getPathString();
          final ObjectId objectId = tw.getObjectId(0);
          commitEntries.put(pathString, new FileEntry(fileMode, objectId));
          alreadyExistingFiles.put(objectId, pathString);
        }
      }

      return new Updater() {
        private boolean modified = false;

        @Override
        public synchronized boolean importFile(final Path file, final String name)
            throws IOException {
          try (final ObjectInserter objectInserter = repository.newObjectInserter()) {
            final ObjectId newFileId =
                objectInserter.insert(
                    Constants.OBJ_BLOB, Files.size(file), Files.newInputStream(file));
            final String existingFileName = alreadyExistingFiles.get(newFileId);
            if (existingFileName != null) {
              log.info("File " + file + " already imported as " + existingFileName + " -> merging");
              return true;
            }
            final FileEntry existingEntry = commitEntries.get(name);
            if (existingEntry == null || !existingEntry.getBlob().equals(newFileId)) {
              commitEntries.put(name, new FileEntry(FileMode.REGULAR_FILE, newFileId));
              alreadyExistingFiles.put(newFileId, name);
              modified = true;
            }
          }
          return true;
        }

        @Override
        public synchronized boolean commit() {
          if (!modified) return true;
          try (final ObjectInserter objectInserter = repository.newObjectInserter()) {
            final Optional<Ref> currentMasterRef = findMasterRef();
            final TreeFormatter treeFormatter = new TreeFormatter(commitEntries.size());
            commitEntries.forEach(
                (key, value) -> treeFormatter.append(key, value.getMode(), value.getBlob()));
            final ObjectId treeId = treeFormatter.insertTo(objectInserter);
            final CommitBuilder commit = new CommitBuilder();
            final PersonIdent author = new PersonIdent("raoa-importer", "photos@teamkoenig.ch");
            commit.setAuthor(author);
            commit.setCommitter(author);
            commit.setParentIds(currentMasterRef.get().getObjectId());
            commit.setTreeId(treeId);
            final ObjectId commitId = objectInserter.insert(commit);

            objectInserter.flush();

            RevCommit revCommit = repository.parseCommit(commitId);
            RefUpdate ru = repository.updateRef(Constants.HEAD);
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
                log.info("Commit successful " + repository.getDirectory());
                return true;
            }

            return false;
          } catch (IOException e) {
            log.warn("Cannot finish commit", e);
            return false;
          }
        }
      };
    } catch (IOException ex) {
      throw new RuntimeException("Cannot read master branch", ex);
    }
  }

  @Value
  private static class FileEntry {
    private FileMode mode;
    private AnyObjectId blob;
  }
}
