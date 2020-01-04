package ch.bergturbenthal.raoa.coordinator.service.impl;

import ch.bergturbenthal.raoa.coordinator.service.RemoteImageProcessor;
import ch.bergturbenthal.raoa.libs.model.elasticsearch.AlbumEntryData;
import ch.bergturbenthal.raoa.libs.model.kafka.ProcessImageRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

@Service
public class KafkaRemoteImageProcessor implements RemoteImageProcessor {
  private final KafkaTemplate<ObjectId, ProcessImageRequest> kafkaTemplate;
  private final MeterRegistry meterRegistry;
  private final Map<ObjectId, MonoSink<AlbumEntryData>> waitingResponses =
      new ConcurrentHashMap<>();

  public KafkaRemoteImageProcessor(
      final KafkaTemplate<ObjectId, ProcessImageRequest> kafkaTemplate,
      final MeterRegistry meterRegistry) {
    this.kafkaTemplate = kafkaTemplate;
    this.meterRegistry = meterRegistry;
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
                waitingResponses.remove(fileId);
              });
          sink.onDispose(
              () -> {
                meterRegistry
                    .timer("processor.remote")
                    .record(Duration.ofNanos(System.nanoTime() - startTime));
              });
        });
  }

  @KafkaListener(id = "image-coordinator", topics = "processed-image", clientIdPrefix = "imgCoord")
  public void takeProcessedImageResponse(AlbumEntryData data) {
    final ObjectId entryId = data.getEntryId();
    final MonoSink<AlbumEntryData> foundSink = waitingResponses.remove(entryId);
    if (foundSink != null) {
      foundSink.success(data);
    }
  }
}
