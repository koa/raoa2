package ch.bergturbenthal.raoa.processor.image.interfaces;

import ch.bergturbenthal.raoa.libs.model.elasticsearch.AlbumEntryData;
import ch.bergturbenthal.raoa.libs.model.kafka.ProcessImageRequest;
import ch.bergturbenthal.raoa.processor.image.service.ImageProcessor;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class KafkaMessageReceiver {
  private final ImageProcessor imageProcessor;
  private final KafkaTemplate<ObjectId, AlbumEntryData> kafkaTemplate;

  public KafkaMessageReceiver(
      final ImageProcessor imageProcessor,
      final KafkaTemplate<ObjectId, AlbumEntryData> kafkaTemplate) {
    this.imageProcessor = imageProcessor;
    this.kafkaTemplate = kafkaTemplate;
  }

  @KafkaListener(topics = "process-image", groupId = "imgProc")
  public void processImage(ConsumerRecord<ObjectId, ProcessImageRequest> record) {
    final ProcessImageRequest request = record.value();
    final ObjectId key = record.key();
    // log.info("Should process " + request);
    final String filename = request.getFilename();
    final UUID albumId = request.getAlbumId();
    final Mono<AlbumEntryData> loadedData = imageProcessor.processImage(albumId, filename);
    loadedData
        .flatMap(
            (AlbumEntryData data) ->
                Mono.fromFuture(kafkaTemplate.send("processed-image", key, data).completable()))
        .log("img: " + request.getFilename())
        .block();
  }
}
