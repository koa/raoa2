package ch.bergturbenthal.raoa.viewer.interfaces;

import ch.bergturbenthal.raoa.elastic.model.UploadedFile;
import ch.bergturbenthal.raoa.elastic.model.User;
import ch.bergturbenthal.raoa.elastic.repository.UploadedFileRepository;
import ch.bergturbenthal.raoa.libs.model.UploadResult;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.UploadFilenameService;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Mono;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Controller
@RequestMapping("rest/import")
public class ImportController {
    private final AlbumList albumList;
    private final UploadFilenameService uploadFilenameService;
    private final AuthorizationManager authorizationManager;
    private final UploadedFileRepository uploadedFileRepository;

    public ImportController(final AlbumList albumList, final UploadFilenameService uploadFilenameService,
            final AuthorizationManager authorizationManager, final UploadedFileRepository uploadedFileRepository) {
        this.albumList = albumList;
        this.uploadFilenameService = uploadFilenameService;
        this.authorizationManager = authorizationManager;
        this.uploadedFileRepository = uploadedFileRepository;
    }

    @PostMapping("{filename}")
    @ResponseBody
    public Mono<ResponseEntity<UploadResult>> importFile(InputStream inputStream, final @PathVariable String filename)
            throws IOException {
        Instant startImport = Instant.now();
        final Optional<User> optionalUser = authorizationManager.currentUser(SecurityContextHolder.getContext())
                .blockOptional();
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
            Instant startStore = Instant.now();
            final OutputStream fos = new FileOutputStream(tempFile);
            final long byteCount = IOUtils.copyLarge(inputStream, fos);
            Instant startDetect = Instant.now();

            AtomicReference<Instant> startSave = new AtomicReference<>();

            return albumList.detectTargetAlbum(tempFile.toPath()).doOnNext(v -> startSave.set(Instant.now()))
                    .map(detectedAlbum -> UploadedFile.builder().fileId(fileId).uploadedUser(user.getId())
                            .filename(filename).uploadTime(Instant.now()).suggestedAlbum(detectedAlbum).build())
                    .flatMap(uploadedFileRepository::save)
                    .map(file -> new UploadResult(fileId, byteCount, file.getSuggestedAlbum())).map(body -> {
                        final Instant startSaveInstant = startSave.get();
                        final Duration prepareTime = Duration.between(startImport, startStore);
                        final Duration transferTime = Duration.between(startStore, startDetect);
                        final Duration detectTime = Duration.between(startDetect, startSaveInstant);
                        final Duration saveEntryTime = Duration.between(startSaveInstant, Instant.now());
                        return ResponseEntity.ok()
                                .header("Server-Timing",
                                        createTiming(prepareTime, "identify", "Identify User") + ", "
                                                + createTiming(transferTime, "upload", "Upload Data") + ", "
                                                + createTiming(detectTime, "detect", "detect Album") + ", "
                                                + createTiming(saveEntryTime, "save", "Save Result"))
                                .body(body);
                    }).doOnError(ex -> log.warn("Cannot import file {}", filename, ex));
        } catch (IOException ex) {
            log.warn("Cannot create file " + tempFile, ex);
            tempFile.delete();
            throw ex;
        }
    }

    @NotNull
    private static String createTiming(final Duration prepareTime, final String key, final String description) {
        return key + ";dur=" + prepareTime.toMillis() + ";desc=\"" + description + "\"";
    }
}
