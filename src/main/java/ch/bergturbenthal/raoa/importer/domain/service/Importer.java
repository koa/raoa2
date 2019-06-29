package ch.bergturbenthal.raoa.importer.domain.service;

import java.io.IOException;
import java.nio.file.Path;

public interface Importer {
  void importDirectories(Path src, Path target) throws IOException;
}
