package ch.bergturbenthal.raoa.viewer.interfaces;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.FileCache;
import ch.bergturbenthal.raoa.viewer.service.FileCacheManager;
import ch.bergturbenthal.raoa.viewer.service.ThumbnailManager;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.ModelAndView;
import reactor.core.Disposable;
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
  private final FileCache<UUID> imageZipCache;
  private final ViewerProperties viewerProperties;

  public AlbumListController(
      final AlbumList albumList,
      final FileCacheManager fileCacheManager,
      final ThumbnailManager thumbnailManager,
      final ViewerProperties viewerProperties) {
    this.albumList = albumList;
    this.fileCacheManager = fileCacheManager;
    this.thumbnailManager = thumbnailManager;
    imageZipCache =
        fileCacheManager.createCache("album-zip", this::imageZip, uuid -> uuid.toString() + ".zip");
    this.viewerProperties = viewerProperties;
  }

  private Mono<File> imageZip(UUID album, File targetDir) {

    final FileCache<ObjectId> thumbnailCache = thumbnailCache(album);
    return Mono.justOrEmpty(albumList.getAlbum(album))
        .flatMapIterable(
            gitAccess -> {
              try {
                return gitAccess.listFiles(IMAGE_FILE_FILTER);
              } catch (IOException e) {
                throw new RuntimeException("Cannot list files of album " + album, e);
              }
            })
        .flatMap(
            entry -> thumbnailCache.take(entry.getFileId()).map(f -> Tuples.of(entry, f)),
            viewerProperties.getConcurrentThumbnailers())
        .log("input")
        .collect(
            () -> {
              try {
                final File tempFile = new File(targetDir, UUID.randomUUID().toString());
                final ZipOutputStream zipOutputStream =
                    new ZipOutputStream(new FileOutputStream(tempFile));
                return Tuples.of(tempFile, zipOutputStream);
              } catch (FileNotFoundException e) {
                throw new RuntimeException("Cannot init file", e);
              }
            },
            (Tuple2<File, ZipOutputStream> t, Tuple2<GitAccess.GitFileEntry, File> file) -> {
              try {
                final File outputFile = t.getT1();
                final ZipOutputStream outputStream = t.getT2();
                synchronized (outputFile) {
                  outputStream.putNextEntry(new ZipEntry(file.getT1().getNameString()));
                  @Cleanup
                  final FileInputStream fileInputStream = new FileInputStream(file.getT2());
                  IOUtils.copy(fileInputStream, outputStream);
                }
              } catch (IOException e) {
                throw new RuntimeException("Cannot append " + file.getT2() + " to " + t.getT1());
              }
            })
        .map(
            t -> {
              try {
                t.getT2().close();
              } catch (IOException e) {
                throw new RuntimeException("Cannot close " + t.getT1(), e);
              }
              return t.getT1();
            })
        .log("Output");
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
  public @ResponseBody Mono<Resource> takeThumbnail(
      @PathVariable("albumId") UUID albumId, @PathVariable("imageId") String fileId) {
    final ObjectId objectId = ObjectId.fromString(fileId);
    return thumbnailCache(albumId).take(objectId).map(FileSystemResource::new);
  }

  @GetMapping("album-zip/{albumId}")
  public @ResponseBody DeferredResult<HttpEntity<FileSystemResource>> takeZip(
      @PathVariable("albumId") UUID albumId) {

    final DeferredResult<HttpEntity<FileSystemResource>> deferredResult =
        new DeferredResult<>(Duration.ofMinutes(30).toMillis());
    final Mono<String> fileNameMono =
        Mono.justOrEmpty(albumList.getAlbum(albumId)).map(GitAccess::getName);
    final Mono<FileSystemResource> fileContentMono =
        imageZipCache.take(albumId).map(FileSystemResource::new);
    final Disposable subscription =
        Mono.zip(fileNameMono, fileContentMono)
            .map(
                data -> {
                  final String filename = data.getT1() + ".zip";
                  final FileSystemResource resource = data.getT2();
                  final HttpHeaders headers = new HttpHeaders();
                  headers.setContentType(MediaType.parseMediaType("application/zip"));
                  headers.setContentDisposition(
                      ContentDisposition.builder("attachment").filename(filename).build());
                  return new HttpEntity<>(resource, headers);
                })
            .subscribe(deferredResult::setResult, deferredResult::setErrorResult);

    deferredResult.onCompletion(subscription::dispose);
    return deferredResult;
  }

  @Value
  private static class AlbumListEntry {
    private UUID id;
    private String name;
  }
}
