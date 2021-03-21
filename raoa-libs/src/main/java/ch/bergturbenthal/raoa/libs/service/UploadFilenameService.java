package ch.bergturbenthal.raoa.libs.service;

import java.io.File;
import java.util.UUID;

public interface UploadFilenameService {
  public File createTempUploadFile(UUID id);
}
