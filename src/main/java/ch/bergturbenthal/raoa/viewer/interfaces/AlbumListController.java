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
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

@Slf4j
@Controller
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
  private final FileCacheManager fileCacheManager;
  private final Map<UUID, FileCache<ObjectId>> fileCaches = new ConcurrentHashMap<>();
  private final ThumbnailManager thumbnailManager;
  private final ViewerProperties viewerProperties;

  public AlbumListController(
      final AlbumList albumList,
      final FileCacheManager fileCacheManager,
      final ThumbnailManager thumbnailManager,
      final ViewerProperties viewerProperties) {
    this.albumList = albumList;
    this.fileCacheManager = fileCacheManager;
    this.thumbnailManager = thumbnailManager;
    this.viewerProperties = viewerProperties;
  }

  private FileCache<ObjectId> thumbnailCache(UUID album) {

    return fileCaches.computeIfAbsent(
        album,
        (UUID k) -> {
          final Optional<GitAccess> access = albumList.getAlbum(k);
          return access
              .map(
                  gitAccess -> {
                    final File cacheDir = new File("/tmp", gitAccess.getName());
                    if (!cacheDir.exists()) cacheDir.mkdirs();
                    return fileCacheManager.<ObjectId>createCache(
                        "thumbnail-" + album,
                        (objectId, targetDir) -> {
                          try {
                            return thumbnailManager.takeThumbnail(
                                objectId,
                                gitAccess.readObject(objectId).orElseThrow(),
                                MediaType.IMAGE_JPEG,
                                targetDir);
                          } catch (IOException e) {
                            throw new RuntimeException("Cannot create thumbnail", e);
                          }
                        },
                        id -> id.name() + ".jpg");
                  })
              .orElseThrow();
        });
  }

  @GetMapping("album")
  public Mono<ModelAndView> listAlbums() {
    final Mono<List<AlbumListEntry>> albumList =
        Flux.fromStream(this.albumList.listAlbums())
            .map(
                foundAlbum ->
                    new AlbumListEntry(foundAlbum.getAlbumId(), foundAlbum.getAccess().getName()))
            .collectSortedList(Comparator.comparing(AlbumListEntry::getName));
    return albumList.map(
        l -> new ModelAndView("list-albums", Collections.singletonMap("albums", l)));
  }

  @GetMapping("album/{albumId}")
  public Mono<ModelAndView> listAlbumContent(@PathVariable("albumId") UUID albumId) {
    return Mono.justOrEmpty(
        albumList
            .getAlbum(albumId)
            .map(
                a -> {
                  try {
                    return a.listFiles(IMAGE_FILE_FILTER);
                  } catch (IOException e) {
                    throw new RuntimeException("Cannot list files", e);
                  }
                })
            .map(files -> Map.of("entries", files, "albumId", albumId))
            .map(variables -> new ModelAndView("list-album", variables)));
  }

  @GetMapping("album/{albumId}/{imageId}")
  public @ResponseBody Mono<HttpEntity<Resource>> takeThumbnail(
      @PathVariable("albumId") UUID albumId, @PathVariable("imageId") String fileId) {
    final ObjectId objectId = ObjectId.fromString(fileId);
    final Mono<FileSystemResource> resourceMono =
        thumbnailCache(albumId).take(objectId).map(FileSystemResource::new);
    final Mono<String> filenameMono =
        Mono.justOrEmpty(
            albumList
                .getAlbum(albumId)
                .flatMap(
                    g -> {
                      try {
                        return g.listFiles(IMAGE_FILE_FILTER).stream()
                            .filter(e -> e.getFileId().name().equals(fileId))
                            .findAny();
                      } catch (IOException e) {
                        throw new RuntimeException("Cannot load entry", e);
                      }
                    })
                .map(GitAccess.GitFileEntry::getNameString));
    return Mono.zip(filenameMono, resourceMono)
        .map(
            t -> {
              final HttpHeaders headers = new HttpHeaders();
              headers.setContentType(MediaType.IMAGE_JPEG);
              headers.setContentDisposition(
                  ContentDisposition.builder("attachment").filename(t.getT1()).build());
              return new HttpEntity<>(t.getT2(), headers);
            });
  }

  @GetMapping("album-zip/{albumId}")
  public void generateZip(@PathVariable("albumId") UUID album, HttpServletResponse response)
      throws IOException {
    final FileCache<ObjectId> thumbnailCache = thumbnailCache(album);
    final GitAccess gitAccess = albumList.getAlbum(album).orElseThrow();
    final String filename = gitAccess.getName() + ".zip";

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
        Flux.fromIterable(gitAccess.listFiles(IMAGE_FILE_FILTER))
            .flatMap(
                entry -> thumbnailCache.take(entry.getFileId()).map(f -> Tuples.of(entry, f)),
                viewerProperties.getConcurrentThumbnailers())
            .toIterable()) {
      zipOutputStream.putNextEntry(new ZipEntry(fileData.getT1().getNameString()));
      @Cleanup final FileInputStream fileInputStream = new FileInputStream(fileData.getT2());
      IOUtils.copy(fileInputStream, zipOutputStream);
    }
  }

  @Value
  private static class AlbumListEntry {
    private UUID id;
    private String name;
  }
}
