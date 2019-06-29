package ch.bergturbenthal.raoa.importer.domain.service;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.ObjectLoader;

public interface GitAccess {
  Optional<ObjectLoader> readFile(String filename) throws IOException;

  Stream<Instant> readAutoadd();

  Updater createUpdater();
}
