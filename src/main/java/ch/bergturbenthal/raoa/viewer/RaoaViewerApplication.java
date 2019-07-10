package ch.bergturbenthal.raoa.viewer;

import ch.bergturbenthal.raoa.libs.RaoaLibConfiguration;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.ThumbnailManager;
import ch.bergturbenthal.raoa.viewer.service.impl.RemoteThumbnailManager;
import java.io.File;
import java.io.IOException;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

@Slf4j
@SpringBootApplication
@EnableConfigurationProperties(ViewerProperties.class)
@Import(RaoaLibConfiguration.class)
@ComponentScan(basePackageClasses = RemoteThumbnailManager.class)
public class RaoaViewerApplication {

  public static void main(String[] args) {
    final ConfigurableApplicationContext applicationContext =
        SpringApplication.run(RaoaViewerApplication.class, args);
    final AlbumList albumList = applicationContext.getBean(AlbumList.class);
    final ThumbnailManager thumbnailManager = applicationContext.getBean(ThumbnailManager.class);

    final Long fileCount =
        Flux.fromStream(albumList.listAlbums())
            .map(AlbumList.FoundAlbum::getAccess)
            .flatMap(
                gitAccess -> {
                  try {
                    return Flux.fromIterable(gitAccess.listFiles(PathSuffixFilter.create(".JPG")))
                        .map(e -> Tuples.of(gitAccess, e));
                  } catch (IOException ex) {
                    return Flux.error(ex);
                  }
                })
            .flatMap(
                e -> {
                  try {
                    final GitAccess gitAccess = e.getT1();
                    final GitAccess.GitFileEntry fileEntry = e.getT2();
                    final Optional<ObjectLoader> optionalObjectLoader =
                        gitAccess.readObject(fileEntry.getFileId());
                    if (optionalObjectLoader.isPresent()) {
                      return thumbnailManager
                          .takeThumbnail(
                              fileEntry.getFileId(),
                              optionalObjectLoader.get(),
                              MediaType.IMAGE_JPEG)
                          .map(f -> Tuples.of(fileEntry.getNameString(), f))
                          .onErrorResume(
                              ex -> {
                                log.warn("Cannot convert file " + fileEntry.getNameString(), ex);
                                return Mono.empty();
                              });
                    }
                    return Mono.empty();
                  } catch (IOException ex) {
                    return Mono.error(ex);
                  }
                })
            .doOnNext(
                e -> {
                  e.getT2().renameTo(new File("/tmp", e.getT1()));
                  log.info("Translated " + e.getT1());
                })
            .count()
            .block();
    log.info("Processed " + fileCount + " files");
  }
}
