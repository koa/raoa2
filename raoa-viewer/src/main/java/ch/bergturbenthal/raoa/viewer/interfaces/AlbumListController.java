package ch.bergturbenthal.raoa.viewer.interfaces;

import ch.bergturbenthal.raoa.elastic.service.impl.ElasticSearchDataViewService;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.ThumbnailFilenameService;
import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import ch.bergturbenthal.raoa.viewer.service.impl.GitBlobRessource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.servlet.http.HttpServletResponse;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

@Slf4j
@Controller
@RequestMapping("rest")
public class AlbumListController {
  public static final TreeFilter IMAGE_FILE_FILTER = ElasticSearchDataViewService.IMAGE_FILE_FILTER;
  public static final ResponseEntity<Resource> NOT_FOUND_RESPONSE =
      new ResponseEntity<>(HttpStatus.NOT_FOUND);
  public static final MediaType NEF = MediaType.parseMediaType("image/x-nikon-nef");
  public static final MediaType VIDEO_THUMBNAIL_TYPE = MediaType.valueOf("video/mp4");
  private static final MediaType TIFF = MediaType.parseMediaType("image/tiff");
  private final AlbumList albumList;
  private final ViewerProperties viewerProperties;
  private final ThumbnailFilenameService thumbnailFilenameService;
  private final AuthorizationManager authorizationManager;
  private final MeterRegistry meterRegistry;
  private final AtomicInteger failCount = new AtomicInteger();

  public AlbumListController(
      final AlbumList albumList,
      final ViewerProperties viewerProperties,
      final ThumbnailFilenameService thumbnailFilenameService,
      final AuthorizationManager authorizationManager,
      MeterRegistry meterRegistry) {
    this.albumList = albumList;
    this.viewerProperties = viewerProperties;
    this.thumbnailFilenameService = thumbnailFilenameService;
    this.authorizationManager = authorizationManager;
    this.meterRegistry = meterRegistry;

    meterRegistry.gauge("limiter2.failed", failCount, AtomicInteger::get);
  }

  @NotNull
  private static MediaType fixContentType(final MediaType mediaType, final String filename) {
    if (mediaType.equals(TIFF) && filename.toLowerCase().endsWith(".nef")) {
      return NEF;
    }
    return mediaType;
  }

  @GetMapping("album/{albumId}/{imageId}/thumbnail")
  public @ResponseBody Mono<ResponseEntity<Resource>> takeThumbnail(
      @PathVariable("albumId") UUID albumId,
      @PathVariable("imageId") String fileId,
      @RequestParam(name = "maxLength", defaultValue = "1600") int maxLength) {
    final ObjectId objectId = ObjectId.fromString(fileId);
    return checkAccessAndReturn(
        albumId,
        () -> {
          final File thumbnailFile =
              thumbnailFilenameService.findThumbnailOf(albumId, objectId, maxLength);
          Resource res = new FileSystemResource(thumbnailFile);
          final HttpHeaders headers = new HttpHeaders();
          headers.setContentType(MediaType.IMAGE_JPEG);
          headers.setCacheControl(CacheControl.maxAge(1, TimeUnit.DAYS));
          headers.setETag("\"" + fileId + ".tmb\"");
          // headers.setContentDisposition(
          //    ContentDisposition.builder("attachment").filename(t.getT1()).build());
          return new ResponseEntity<>(res, headers, HttpStatus.OK);
        });
  }

  @NotNull
  private Mono<ResponseEntity<Resource>> checkAccessAndReturn(
      final UUID albumId, final Supplier<ResponseEntity<Resource>> entitySupplier) {
    return authorizationManager
        .currentUser(SecurityContextHolder.getContext())
        .flatMap(
            user -> {
              final String email = user.getUserData().getEmail();
              final long startTime = System.nanoTime();
              return authorizationManager
                  .canUserAccessToAlbum(albumId, Mono.just(user))
                  .map(
                      allowed -> {
                        if (!allowed) {
                          return new ResponseEntity<Resource>(HttpStatus.FORBIDDEN);
                        }
                        return entitySupplier.get();
                      })
                  .defaultIfEmpty(NOT_FOUND_RESPONSE)
                  .doOnNext(
                      response -> {
                        final Tags tags =
                            Tags.of("result", response.getStatusCode().name(), "user", email);
                        meterRegistry
                            .timer("raoa.download.thumbnail", tags)
                            .record(Duration.ofNanos(System.nanoTime() - startTime));
                        try {
                          final Resource body = response.getBody();
                          if (body != null)
                            meterRegistry
                                .counter("raoa.download.thumbnail.bytes", tags)
                                .increment(body.contentLength());
                        } catch (IOException ex) {
                          log.warn("Cannot determine resource size", ex);
                        }
                      });
            });
  }

  @GetMapping("album/{albumId}/{imageId}/videothumbnail")
  public @ResponseBody Mono<ResponseEntity<Resource>> takeVideoThumbnail(
      @PathVariable("albumId") UUID albumId,
      @PathVariable("imageId") String fileId,
      @RequestParam(name = "maxLength", defaultValue = "1600") int maxLength) {
    final ObjectId objectId = ObjectId.fromString(fileId);
    return checkAccessAndReturn(
        albumId,
        () -> {
          final File thumbnailFile =
              thumbnailFilenameService.findVideoThumbnailOf(albumId, objectId, maxLength);
          Resource res = new FileSystemResource(thumbnailFile);
          final HttpHeaders headers = new HttpHeaders();
          headers.setContentType(VIDEO_THUMBNAIL_TYPE);
          headers.setCacheControl(CacheControl.maxAge(1, TimeUnit.DAYS));
          headers.setETag("\"" + fileId + ".vid\"");
          // headers.setContentDisposition(
          //    ContentDisposition.builder("attachment").filename(t.getT1()).build());
          return new ResponseEntity<>(res, headers, HttpStatus.OK);
        });
  }

  @NotNull
  private ResponseEntity<Resource> createVideoResponse(
      final UUID albumId, final String fileId, final int maxLength, final ObjectId objectId) {
    final File thumbnailFile =
        thumbnailFilenameService.findVideoThumbnailOf(albumId, objectId, maxLength);
    Resource res = new FileSystemResource(thumbnailFile);
    final HttpHeaders headers = new HttpHeaders();
    headers.setContentType(VIDEO_THUMBNAIL_TYPE);
    headers.setCacheControl(CacheControl.maxAge(1, TimeUnit.DAYS));
    headers.setETag("\"" + fileId + "\"");
    // headers.setContentDisposition(
    //    ContentDisposition.builder("attachment").filename(t.getT1()).build());
    return new ResponseEntity<>(res, headers, HttpStatus.OK);
  }

  @GetMapping("album/{albumId}/{imageId}/original")
  public @ResponseBody Mono<HttpEntity<Resource>> takeOriginal(
      @PathVariable("albumId") UUID albumId, @PathVariable("imageId") String fileId) {

    final Mono<GitAccess> access = albumList.getAlbum(albumId).cache();
    final ObjectId entryId = ObjectId.fromString(fileId);

    return authorizationManager
        .currentUser(SecurityContextHolder.getContext())
        .flatMap(
            user -> {
              final String email = user.getUserData().getEmail();
              final long startTime = System.nanoTime();
              return authorizationManager
                  .canUserAccessToAlbum(albumId, Mono.just(user))
                  .filter(t -> t)
                  .flatMap(
                      t ->
                          Mono.zip(
                              access
                                  .flatMap(gitAccess -> gitAccess.entryMetdata(entryId))
                                  .map(
                                      m ->
                                          MediaType.parseMediaType(
                                              m.get(
                                                  org.apache.tika.metadata.HttpHeaders
                                                      .CONTENT_TYPE))),
                              access.flatMap(gitAccess -> gitAccess.readObject(entryId)),
                              access.flatMap(a -> a.filenameOfObject(entryId))))
                  .<HttpEntity<Resource>>map(
                      t -> {
                        MediaType mediaType = fixContentType(t.getT1(), t.getT3());
                        final GitBlobRessource resource =
                            new GitBlobRessource(t.getT2(), mediaType, entryId);
                        final HttpHeaders headers = new HttpHeaders();
                        headers.setContentType(mediaType);
                        headers.setCacheControl(CacheControl.maxAge(1, TimeUnit.DAYS));
                        headers.setETag("\"" + entryId.name() + "\"");
                        return new HttpEntity<>(resource, headers);
                      })
                  .doOnNext(
                      response -> {
                        final Tags tags = Tags.of("user", email);
                        meterRegistry
                            .timer("raoa.download.original", tags)
                            .record(Duration.ofNanos(System.nanoTime() - startTime));
                        try {
                          final Resource body = response.getBody();
                          if (body != null)
                            meterRegistry
                                .counter("raoa.download.original.bytes", tags)
                                .increment(body.contentLength());
                        } catch (IOException ex) {
                          log.warn("Cannot determine resource size", ex);
                        }
                      });
            });
  }

  @GetMapping("album-zip/{albumId}")
  public void generateZip(@PathVariable("albumId") UUID album, HttpServletResponse response)
      throws IOException {
    final SecurityContext securityContext = SecurityContextHolder.getContext();
    final Mono<GitAccess> gitAccess =
        authorizationManager
            .canUserAccessToAlbum(securityContext, album)
            .filter(t -> t)
            .flatMap(t -> albumList.getAlbum(album).cache());
    final Optional<String> optionalFilename =
        gitAccess.flatMap(GitAccess::getName).map(n -> n + ".zip").blockOptional();
    if (optionalFilename.isEmpty()) return;

    final HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType("application/zip"));
    headers.setContentDisposition(
        ContentDisposition.builder("attachment").filename(optionalFilename.get()).build());

    headers.forEach(
        (header, values) -> {
          values.forEach(v -> response.setHeader(header, v));
        });

    @Cleanup
    final ZipOutputStream zipOutputStream = new ZipOutputStream(response.getOutputStream());
    // zipOutputStream.setMethod(ZipOutputStream.STORED);
    for (Tuple2<GitAccess.GitFileEntry, File> fileData :
        gitAccess
            .flatMapMany(g -> g.listFiles(IMAGE_FILE_FILTER))
            .map(
                entry ->
                    Tuples.of(
                        entry,
                        thumbnailFilenameService.findThumbnailOf(album, entry.getFileId(), 1600)))
            .filter(e -> e.getT2().exists())
            .toIterable()) {
      zipOutputStream.putNextEntry(new ZipEntry(fileData.getT1().getNameString()));
      @Cleanup final FileInputStream fileInputStream = new FileInputStream(fileData.getT2());
      IOUtils.copy(fileInputStream, zipOutputStream);
    }
  }
}
