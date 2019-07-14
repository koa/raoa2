package ch.bergturbenthal.raoa.libs.service;

import java.io.IOException;
import java.nio.file.Path;

public interface Updater {
  boolean importFile(Path file, String name) throws IOException;

  boolean commit();

  boolean commit(String message);
}
