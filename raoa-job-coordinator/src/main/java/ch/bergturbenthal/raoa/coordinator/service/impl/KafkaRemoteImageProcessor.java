package ch.bergturbenthal.raoa.coordinator.service.impl;

import ch.bergturbenthal.raoa.coordinator.service.RemoteImageProcessor;
import ch.bergturbenthal.raoa.libs.model.elasticsearch.AlbumEntryData;
import ch.bergturbenthal.raoa.libs.model.kafka.ProcessImageRequest;
import ch.bergturbenthal.raoa.libs.repository.SyncAlbumDataEntryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

@Slf4j
@Service
public class KafkaRemoteImageProcessor implements RemoteImageProcessor {
  private final KafkaTemplate<ObjectId, ProcessImageRequest> kafkaTemplate;
  private final MeterRegistry meterRegistry;
  private final Map<ObjectId, MonoSink<AlbumEntryData>> waitingResponses =
      new ConcurrentHashMap<>();
  private final Collection<AlbumEntryData> additionalFoundResponses = new ArrayList<>();
  private final SyncAlbumDataEntryRepository syncAlbumDataEntryRepository;

  public KafkaRemoteImageProcessor(
      final KafkaTemplate<ObjectId, ProcessImageRequest> kafkaTemplate,
      final MeterRegistry meterRegistry,
      final SyncAlbumDataEntryRepository syncAlbumDataEntryRepository) {
    this.kafkaTemplate = kafkaTemplate;
    this.meterRegistry = meterRegistry;
    this.syncAlbumDataEntryRepository = syncAlbumDataEntryRepository;
    meterRegistry.gauge("processor.pending", waitingResponses, Map::size);
  }

  @Override
  public Mono<AlbumEntryData> processImage(final ObjectId fileId, final ProcessImageRequest data) {

    return Mono.create(
        sink -> {
          final long startTime = System.nanoTime();
          waitingResponses.put(fileId, sink);
          final ListenableFuture<SendResult<ObjectId, ProcessImageRequest>> sent =
              kafkaTemplate.send("process-image", fileId, data);
          sink.onCancel(
              () -> {
                sent.cancel(true);
                waitingResponses.remove(fileId, sink);
              });
          sink.onDispose(
              () -> {
                meterRegistry
                    .timer("processor.remote")
                    .record(Duration.ofNanos(System.nanoTime() - startTime));
              });
        });
  }

  @KafkaListener(topics = "processed-image", groupId = "coordinator")
  public void takeProcessedImageResponse(AlbumEntryData data) {
    final ObjectId entryId = data.getEntryId();
    final MonoSink<AlbumEntryData> foundSink = waitingResponses.remove(entryId);
    log.info("response " + entryId.name() + ", match: " + (foundSink != null));
    if (foundSink != null) {
      foundSink.success(data);
    } else {
      addToQueue(data);
    }
  }

  private synchronized void addToQueue(final AlbumEntryData data) {
    additionalFoundResponses.add(data);
    if (additionalFoundResponses.size() > 2000) flushQueue();
  }

  @Scheduled(fixedDelay = 60 * 1000)
  private synchronized void flushQueue() {
    try {
      syncAlbumDataEntryRepository.saveAll(additionalFoundResponses);
      additionalFoundResponses.clear();
    } catch (Exception ex) {
      log.warn("Cannot store responses", ex);
    }
  }
}
