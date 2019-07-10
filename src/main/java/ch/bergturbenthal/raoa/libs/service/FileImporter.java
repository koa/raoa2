package ch.bergturbenthal.raoa.libs.service;

import java.io.IOException;
import java.nio.file.Path;

public interface FileImporter {
  boolean importFile(Path file) throws IOException;

  boolean commitAll();
}
