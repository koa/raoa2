package ch.bergturbenthal.raoa.viewer.interfaces;

import ch.bergturbenthal.raoa.elastic.model.UploadedFile;
import ch.bergturbenthal.raoa.elastic.model.User;
import ch.bergturbenthal.raoa.elastic.repository.UploadedFileRepository;
import ch.bergturbenthal.raoa.libs.model.UploadResult;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.UploadFilenameService;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import java.io.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Mono;

@Slf4j
@Controller
@RequestMapping("rest/import")
public class ImportController {
  private final AlbumList albumList;
  private final UploadFilenameService uploadFilenameService;
  private final AuthorizationManager authorizationManager;
  private final UploadedFileRepository uploadedFileRepository;

  public ImportController(
      final AlbumList albumList,
      final UploadFilenameService uploadFilenameService,
      final AuthorizationManager authorizationManager,
      final UploadedFileRepository uploadedFileRepository) {
    this.albumList = albumList;
    this.uploadFilenameService = uploadFilenameService;
    this.authorizationManager = authorizationManager;
    this.uploadedFileRepository = uploadedFileRepository;
  }

  @PostMapping("{filename}")
  @ResponseBody
  public Mono<ResponseEntity<UploadResult>> importFile(
      InputStream inputStream, final @PathVariable String filename) throws IOException {
    final Optional<User> optionalUser =
        authorizationManager.currentUser(SecurityContextHolder.getContext()).blockOptional();
    if (optionalUser.isEmpty()) {
      return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
    }
    final User user = optionalUser.get();
    if (!user.isEditor()) {
      return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
    }
    final UUID fileId = UUID.randomUUID();
    final File tempFile = uploadFilenameService.createTempUploadFile(fileId);
    try {
      final OutputStream fos = new FileOutputStream(tempFile);
      final long byteCount = IOUtils.copyLarge(inputStream, fos);

      return albumList
          .detectTargetAlbum(tempFile.toPath())
          .flatMap(
              detectedAlbum ->
                  uploadedFileRepository.save(
                      UploadedFile.builder()
                          .fileId(fileId)
                          .uploadedUser(user.getId())
                          .filename(filename)
                          .uploadTime(Instant.now())
                          .suggestedAlbum(detectedAlbum)
                          .build()))
          .map(file -> new UploadResult(fileId, byteCount, file.getSuggestedAlbum()))
          .map(ResponseEntity::ok);
    } catch (IOException ex) {
      log.warn("Cannot create file " + tempFile, ex);
      tempFile.delete();
      throw ex;
    }
  }
}
