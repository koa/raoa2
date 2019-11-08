package ch.bergturbenthal.raoa.viewer.interfaces;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.impl.Limiter;
import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import ch.bergturbenthal.raoa.viewer.service.FileCache;
import ch.bergturbenthal.raoa.viewer.service.FileCacheManager;
import ch.bergturbenthal.raoa.viewer.service.ThumbnailManager;
import ch.bergturbenthal.raoa.viewer.service.impl.GitBlobRessource;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.servlet.http.HttpServletResponse;
import lombok.Cleanup;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.treewalk.filter.OrTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

@Slf4j
@Controller
@RequestMapping("rest")
public class AlbumListController {
  public static final TreeFilter IMAGE_FILE_FILTER =
      OrTreeFilter.create(
          new TreeFilter[] {
            PathSuffixFilter.create(".jpg"),
            PathSuffixFilter.create(".jpeg"),
            PathSuffixFilter.create(".JPG"),
            PathSuffixFilter.create(".JPEG")
          });
  private final AlbumList albumList;
  private final ViewerProperties viewerProperties;
  private final FileCache<CacheKey> thumbnailCache;
  private final AuthorizationManager authorizationManager;
  private final Limiter limiter;

  public AlbumListController(
      final AlbumList albumList,
      final FileCacheManager fileCacheManager,
      final ThumbnailManager thumbnailManager,
      final ViewerProperties viewerProperties,
      final AuthorizationManager authorizationManager,
      final Limiter limiter) {
    this.albumList = albumList;
    this.viewerProperties = viewerProperties;
    this.authorizationManager = authorizationManager;
    this.limiter = limiter;

    thumbnailCache =
        fileCacheManager.createCache(
            "thumbnails",
            (CacheKey k, File targetDir) -> {
              final Mono<GitAccess> access = albumList.getAlbum(k.getAlbum());
              return limiter.limit(
                  access
                      .flatMap(gitAccess -> gitAccess.readObject(k.getFile()))
                      .flatMap(
                          loader ->
                              thumbnailManager.takeThumbnail(
                                  k.getFile(),
                                  loader,
                                  MediaType.IMAGE_JPEG,
                                  k.getMaxLength(),
                                  targetDir)),
                  "thumbnail");
            },
            cacheKey ->
                cacheKey.getAlbum().toString()
                    + "-"
                    + cacheKey.getFile().name()
                    + "-"
                    + cacheKey.getMaxLength()
                    + ".jpg");
  }

  @GetMapping("album")
  public Mono<ModelAndView> listAlbums() {
    final SecurityContext securityContext = SecurityContextHolder.getContext();

    return this.albumList
        .listAlbums()
        .filterWhen(
            album -> authorizationManager.canUserAccessToAlbum(securityContext, album.getAlbumId()))
        .flatMap(f -> f.getAccess().getName().map(n -> new AlbumListEntry(f.getAlbumId(), n)))
        .collectSortedList(Comparator.comparing(AlbumListEntry::getName))
        .map(l -> new ModelAndView("list-albums", Collections.singletonMap("albums", l)));
  }

  @GetMapping("album/{albumId}")
  public Mono<ModelAndView> listAlbumContent(@PathVariable("albumId") UUID albumId) {
    final SecurityContext securityContext = SecurityContextHolder.getContext();
    return authorizationManager
        .canUserAccessToAlbum(securityContext, albumId)
        .filter(t -> t)
        .flatMap(t -> albumList.getAlbum(albumId))
        .flatMapMany(a -> a.listFiles(IMAGE_FILE_FILTER))
        .collectList()
        .map(files -> Map.of("entries", files, "albumId", albumId))
        .map(variables -> new ModelAndView("list-album", variables));
  }

  @GetMapping("album/{albumId}/{imageId}/thumbnail")
  public @ResponseBody Mono<HttpEntity<Resource>> takeThumbnail(
      @PathVariable("albumId") UUID albumId,
      @PathVariable("imageId") String fileId,
      @RequestParam(name = "maxLength", defaultValue = "1600") int maxLength) {
    final ObjectId objectId = ObjectId.fromString(fileId);
    final SecurityContext securityContext = SecurityContextHolder.getContext();
    return authorizationManager
        .canUserAccessToAlbum(securityContext, albumId)
        .filter(t -> t)
        .flatMap(
            t ->
                Mono.zip(
                    albumList
                        .getAlbum(albumId)
                        .flatMap(
                            g ->
                                g.listFiles(IMAGE_FILE_FILTER)
                                    .filter(e -> e.getFileId().name().equals(fileId))
                                    .take(1)
                                    .singleOrEmpty())
                        .map(GitAccess.GitFileEntry::getNameString),
                    thumbnailCache
                        .take(new CacheKey(albumId, objectId, maxLength))
                        .map(FileSystemResource::new)))
        .map(
            t -> {
              final HttpHeaders headers = new HttpHeaders();
              headers.setContentType(MediaType.IMAGE_JPEG);
              headers.setCacheControl(CacheControl.maxAge(1, TimeUnit.DAYS));
              headers.setETag("\"" + fileId + "\"");
              // headers.setContentDisposition(
              //    ContentDisposition.builder("attachment").filename(t.getT1()).build());
              return new HttpEntity<>(t.getT2(), headers);
            });
  }

  @GetMapping("album/{albumId}/{imageId}/original")
  public @ResponseBody Mono<HttpEntity<Resource>> takeOriginal(
      @PathVariable("albumId") UUID albumId, @PathVariable("imageId") String fileId) {

    final Mono<GitAccess> access = albumList.getAlbum(albumId);
    final ObjectId entryId = ObjectId.fromString(fileId);
    final SecurityContext securityContext = SecurityContextHolder.getContext();
    return limiter.limit(
        authorizationManager
            .canUserAccessToAlbum(securityContext, albumId)
            .filter(t -> t)
            .flatMap(
                t ->
                    Mono.zip(
                        access
                            .flatMap(gitAccess -> gitAccess.entryMetdata(entryId))
                            .map(m -> m.get(org.apache.tika.metadata.HttpHeaders.CONTENT_TYPE)),
                        access.flatMap(gitAccess -> gitAccess.readObject(entryId))))
            .map(
                t -> {
                  final MediaType mediaType = MediaType.parseMediaType(t.getT1());
                  final GitBlobRessource resource =
                      new GitBlobRessource(t.getT2(), mediaType, entryId);
                  final HttpHeaders headers = new HttpHeaders();
                  headers.setContentType(mediaType);
                  headers.setCacheControl(CacheControl.maxAge(1, TimeUnit.DAYS));
                  headers.setETag("\"" + entryId.name() + "\"");
                  return new HttpEntity<>(resource, headers);
                }),
        "original");
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
            .flatMap(
                entry ->
                    thumbnailCache
                        .take(new CacheKey(album, entry.getFileId(), 1600))
                        .map(f -> Tuples.of(entry, f)),
                viewerProperties.getConcurrentThumbnailers())
            .toIterable()) {
      zipOutputStream.putNextEntry(new ZipEntry(fileData.getT1().getNameString()));
      @Cleanup final FileInputStream fileInputStream = new FileInputStream(fileData.getT2());
      IOUtils.copy(fileInputStream, zipOutputStream);
    }
  }

  @Value
  private static class CacheKey {
    private UUID album;
    private ObjectId file;
    private int maxLength;
  }

  @Value
  private static class AlbumListEntry {
    private UUID id;
    private String name;
  }
}
