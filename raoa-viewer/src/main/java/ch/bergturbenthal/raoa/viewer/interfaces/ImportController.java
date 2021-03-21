package ch.bergturbenthal.raoa.viewer.interfaces;

import ch.bergturbenthal.raoa.libs.model.UploadResult;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.UploadFilenameService;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import java.io.*;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Slf4j
@Controller
@RequestMapping("rest/import")
public class ImportController {
  private final AlbumList albumList;
  private final UploadFilenameService uploadFilenameService;
  private final AuthorizationManager authorizationManager;

  public ImportController(
      final AlbumList albumList,
      final UploadFilenameService uploadFilenameService,
      final AuthorizationManager authorizationManager) {
    this.albumList = albumList;
    this.uploadFilenameService = uploadFilenameService;
    this.authorizationManager = authorizationManager;
  }

  @PostMapping
  @ResponseBody
  public ResponseEntity<UploadResult> importFile(InputStream inputStream) throws IOException {
    if (!authorizationManager.isUserAuthenticated(SecurityContextHolder.getContext())) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    final UUID fileId = UUID.randomUUID();
    final File tempFile = uploadFilenameService.createTempUploadFile(fileId);
    try {
      final OutputStream fos = new FileOutputStream(tempFile);
      final long byteCount = IOUtils.copyLarge(inputStream, fos);
      return ResponseEntity.ok(new UploadResult(fileId, byteCount));
    } catch (IOException ex) {
      log.warn("Cannot create file " + tempFile, ex);
      tempFile.delete();
      throw ex;
    }
  }
}
