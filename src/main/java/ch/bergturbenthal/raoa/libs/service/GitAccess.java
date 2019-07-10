package ch.bergturbenthal.raoa.libs.service;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.Value;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

public interface GitAccess {
  Collection<GitFileEntry> listFiles(TreeFilter filter) throws IOException;

  Optional<ObjectLoader> readObject(AnyObjectId fileId) throws IOException;

  Optional<ObjectLoader> readObject(String filename) throws IOException;

  Stream<Instant> readAutoadd();

  Updater createUpdater();

  String getName();

  @Value
  class GitFileEntry {
    final String nameString;
    final FileMode fileMode;
    final ObjectId fileId;
  }
}
