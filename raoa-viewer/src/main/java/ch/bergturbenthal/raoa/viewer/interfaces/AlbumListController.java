package ch.bergturbenthal.raoa.viewer.interfaces;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.FileCache;
import ch.bergturbenthal.raoa.viewer.service.FileCacheManager;
import ch.bergturbenthal.raoa.viewer.service.ThumbnailManager;
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

  public AlbumListController(
      final AlbumList albumList,
      final FileCacheManager fileCacheManager,
      final ThumbnailManager thumbnailManager,
      final ViewerProperties viewerProperties) {
    this.albumList = albumList;
    this.viewerProperties = viewerProperties;

    thumbnailCache =
        fileCacheManager.createCache(
            "thumbnails",
            (CacheKey k, File targetDir) -> {
              final Mono<GitAccess> access = albumList.getAlbum(k.getAlbum());
              return access
                  .flatMap(gitAccess -> gitAccess.readObject(k.getFile()))
                  .flatMap(
                      loader ->
                          thumbnailManager.takeThumbnail(
                              k.getFile(),
                              loader,
                              MediaType.IMAGE_JPEG,
                              k.getMaxLength(),
                              targetDir));
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
    final Mono<List<AlbumListEntry>> albumList =
        this.albumList
            .listAlbums()
            .flatMap(f -> f.getAccess().getName().map(n -> new AlbumListEntry(f.getAlbumId(), n)))
            .collectSortedList(Comparator.comparing(AlbumListEntry::getName));
    return albumList.map(
        l -> new ModelAndView("list-albums", Collections.singletonMap("albums", l)));
  }

  @GetMapping("album/{albumId}")
  public Mono<ModelAndView> listAlbumContent(@PathVariable("albumId") UUID albumId) {
    return albumList
        .getAlbum(albumId)
        .flatMapMany(
            a -> {
              return a.listFiles(IMAGE_FILE_FILTER);
            })
        .collectList()
        .map(files -> Map.of("entries", files, "albumId", albumId))
        .map(variables -> new ModelAndView("list-album", variables));
  }

  @GetMapping("album/{albumId}/{imageId}")
  public @ResponseBody Mono<HttpEntity<Resource>> takeThumbnail(
      @PathVariable("albumId") UUID albumId,
      @PathVariable("imageId") String fileId,
      @RequestParam(name = "maxLength", defaultValue = "1600") int maxLength) {
    final ObjectId objectId = ObjectId.fromString(fileId);
    final Mono<FileSystemResource> resourceMono =
        thumbnailCache
            .take(new CacheKey(albumId, objectId, maxLength))
            .map(FileSystemResource::new);
    final Mono<String> filenameMono =
        albumList
            .getAlbum(albumId)
            .flatMap(
                g ->
                    g.listFiles(IMAGE_FILE_FILTER)
                        .filter(e -> e.getFileId().name().equals(fileId))
                        .singleOrEmpty())
            .map(GitAccess.GitFileEntry::getNameString);
    return Mono.zip(filenameMono, resourceMono)
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

  @GetMapping("album-zip/{albumId}")
  public void generateZip(@PathVariable("albumId") UUID album, HttpServletResponse response)
      throws IOException {
    final Mono<GitAccess> gitAccess = albumList.getAlbum(album).cache();
    final String filename = gitAccess.flatMap(GitAccess::getName).map(n -> n + ".zip").block();

    final HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType("application/zip"));
    headers.setContentDisposition(
        ContentDisposition.builder("attachment").filename(filename).build());

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
