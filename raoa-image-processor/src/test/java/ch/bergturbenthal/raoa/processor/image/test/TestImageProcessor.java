package ch.bergturbenthal.raoa.processor.image.test;

import ch.bergturbenthal.raoa.elastic.service.impl.ElasticSearchDataViewService;
import ch.bergturbenthal.raoa.libs.properties.Properties;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.AsyncService;
import ch.bergturbenthal.raoa.libs.service.ThumbnailFilenameService;
import ch.bergturbenthal.raoa.libs.service.Updater;
import ch.bergturbenthal.raoa.libs.service.impl.BareAlbumList;
import ch.bergturbenthal.raoa.libs.service.impl.DefaultThumbnailFilenameService;
import ch.bergturbenthal.raoa.libs.service.impl.ExecutorAsyncService;
import ch.bergturbenthal.raoa.processor.image.service.impl.DefaultImageProcessor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

@Slf4j
public class TestImageProcessor {
  @Test
  public void testRawImage() throws IOException, GitAPIException {

    final Properties properties = new Properties();
    final File thumbnailDir = File.createTempFile("raoa", "thumbnail");
    thumbnailDir.delete();
    thumbnailDir.mkdirs();
    properties.setThumbnailDir(thumbnailDir);
    final File repoDir = File.createTempFile("raoa", "repository");
    repoDir.delete();
    repoDir.mkdirs();
    Git.init().setDirectory(new File(repoDir, ".meta.git")).setBare(true).call();
    properties.setRepository(repoDir);

    final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    final AsyncService asyncService = new ExecutorAsyncService(properties);
    final AlbumList albumList = new BareAlbumList(properties, meterRegistry, asyncService);
    final List<File> testfiles =
        Stream.of(
                new ClassPathResource("testimages/_DSC3241.JPG"),
                new ClassPathResource("testimages/_DSC3267.NEF"))
            .map(
                cp -> {
                  try {
                    return cp.getFile();
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                })
            .collect(Collectors.toList());
    final RetryBackoffSpec retrySpec = Retry.backoff(5, Duration.ofMillis(400));

    final Updater.CommitContext context = Updater.CommitContext.builder().build();
    final Boolean importOk =
        albumList
            .createAlbum(Collections.singletonList("test"))
            .flatMap(albumList::getAlbum)
            .flatMap(
                ga ->
                    ga.updateAutoadd(
                            Collections.singleton(Instant.parse("2020-01-01T00:00:00.000Z")),
                            context)
                        .retryWhen(retrySpec)
                        .map(done -> albumList.createImporter(context))
                        .flatMap(
                            fileImporter ->
                                Flux.fromIterable(testfiles)
                                    .flatMap(
                                        file ->
                                            fileImporter
                                                .importFile(file.toPath())
                                                .retryWhen(retrySpec))
                                    .collectList()
                                    .flatMap(
                                        file -> fileImporter.commitAll().retryWhen(retrySpec))))
            .block(Duration.ofMinutes(1));
    Assert.assertTrue(importOk != null && importOk);

    final ThumbnailFilenameService filenameService =
        new DefaultThumbnailFilenameService(properties);
    final DefaultImageProcessor imageProcessor =
        new DefaultImageProcessor(albumList, asyncService, filenameService, meterRegistry);
    final Boolean ok =
        albumList
            .listAlbums()
            .retryWhen(retrySpec)
            .flatMap(
                album ->
                    album
                        .getAccess()
                        .listFiles(ElasticSearchDataViewService.IMAGE_FILE_FILTER)
                        .flatMap(
                            gitFileEntry ->
                                imageProcessor
                                    .processImage(album.getAlbumId(), gitFileEntry.getNameString())
                                    .map(
                                        data -> {
                                          final File bigFile =
                                              filenameService.findThumbnailOf(
                                                  album.getAlbumId(),
                                                  gitFileEntry.getFileId(),
                                                  1600);
                                          log.info(
                                              bigFile
                                                  + ": "
                                                  + data.getContentType()
                                                  + ": "
                                                  + bigFile.length());
                                          return bigFile.exists() && bigFile.length() > 100 * 1024;
                                        })))
            .all(v -> v)
            .block();
    Assert.assertTrue(ok != null && ok);
    FileUtils.deleteDirectory(repoDir);
    FileUtils.deleteDirectory(thumbnailDir);
  }
}
