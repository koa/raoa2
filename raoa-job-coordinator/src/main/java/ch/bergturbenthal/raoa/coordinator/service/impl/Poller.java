package ch.bergturbenthal.raoa.coordinator.service.impl;

import ch.bergturbenthal.raoa.libs.model.kafka.ProcessImageRequest;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.ThumbnailFilenameService;
import ch.bergturbenthal.raoa.libs.service.impl.ElasticSearchDataViewService;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

@Slf4j
@Service
public class Poller {

  private final KafkaTemplate<ObjectId, ProcessImageRequest> kafkaTemplate;
  private final AlbumList albumList;
  private final ElasticSearchDataViewService elasticSearchDataViewService;
  private final ThumbnailFilenameService thumbnailFilenameService;

  public Poller(
      final KafkaTemplate<ObjectId, ProcessImageRequest> kafkaTemplate,
      final AlbumList albumList,
      final ElasticSearchDataViewService elasticSearchDataViewService,
      final ThumbnailFilenameService thumbnailFilenameService) {
    this.kafkaTemplate = kafkaTemplate;
    this.albumList = albumList;
    this.elasticSearchDataViewService = elasticSearchDataViewService;
    this.thumbnailFilenameService = thumbnailFilenameService;
  }

  @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 1000)
  public void poll() {
    final Flux<SendResult<ObjectId, ProcessImageRequest>> take =
        albumList
            .listAlbums()
            .flatMap(
                album ->
                    album
                        .getAccess()
                        .listFiles(ElasticSearchDataViewService.IMAGE_FILE_FILTER)
                        .filterWhen(
                            gitFileEntry -> {
                              final boolean allThumbnailsOk =
                                  thumbnailFilenameService
                                      .listThumbnailsOf(
                                          album.getAlbumId(), gitFileEntry.getFileId())
                                      .map(ThumbnailFilenameService.FileAndScale::getFile)
                                      .map(File::exists)
                                      .collect(
                                          () -> new AtomicBoolean(true),
                                          (r, v) -> r.compareAndSet(true, v),
                                          (r1, r2) -> new AtomicBoolean(r1.get() && r2.get()))
                                      .get();
                              if (!allThumbnailsOk) return Mono.just(true);
                              return elasticSearchDataViewService
                                  .loadEntry(album.getAlbumId(), gitFileEntry.getFileId())
                                  .map(d -> true)
                                  .defaultIfEmpty(false)
                                  .map(b -> !b);
                            })
                        .map(e -> Tuples.of(album, e)))
            // .take(500)
            .flatMap(
                (Tuple2<AlbumList.FoundAlbum, GitAccess.GitFileEntry> entry) -> {
                  final String filename = entry.getT2().getNameString();
                  final ProcessImageRequest data =
                      new ProcessImageRequest(entry.getT1().getAlbumId(), filename);
                  final ObjectId fileId = entry.getT2().getFileId();
                  final ListenableFuture<SendResult<ObjectId, ProcessImageRequest>> send =
                      kafkaTemplate.send("process-image", fileId, data);
                  return Mono.fromFuture(send.completable());
                });
    try {
      for (SendResult<ObjectId, ProcessImageRequest> entry : take.toIterable()) {
        log.info("Sent: " + entry);
      }
    } catch (Exception ex) {
      log.error("Cannot load data", ex);
    }
  }
}
