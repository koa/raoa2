package ch.bergturbenthal.raoa.viewer;

import ch.bergturbenthal.raoa.libs.RaoaLibConfiguration;
import ch.bergturbenthal.raoa.viewer.interfaces.AlbumListController;
import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.impl.RemoteThumbnailManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@SpringBootApplication
@EnableConfigurationProperties(ViewerProperties.class)
@Import(RaoaLibConfiguration.class)
@ComponentScan(basePackageClasses = {RemoteThumbnailManager.class, AlbumListController.class})
@EnableScheduling
public class RaoaViewerApplication {

  public static void main(String[] args) {
    SpringApplication.run(RaoaViewerApplication.class, args);
    /*
    try (final ConfigurableApplicationContext applicationContext =
        SpringApplication.run(RaoaViewerApplication.class, args)) {
      final AlbumList albumList = applicationContext.getBean(AlbumList.class);
      final ThumbnailManager thumbnailManager = applicationContext.getBean(ThumbnailManager.class);

      Instant startTime = Instant.now();
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
      Instant endTime = Instant.now();
      final Duration executeDuration = Duration.between(startTime, endTime);
      log.info("Processed " + fileCount + " files");
      log.info("Used " + executeDuration.getSeconds() + "s");
      double filesPerSecond = fileCount * 1000.0 / executeDuration.toMillis();
      log.info("Speed: " + filesPerSecond + " Files/s");
    }
    */
  }
}
