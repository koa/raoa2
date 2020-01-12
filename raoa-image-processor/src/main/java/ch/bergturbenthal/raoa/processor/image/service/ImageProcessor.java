package ch.bergturbenthal.raoa.processor.image.service;

import ch.bergturbenthal.raoa.libs.model.elasticsearch.AlbumEntryData;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import reactor.core.publisher.Mono;

public interface ImageProcessor {
  @NotNull
  Mono<AlbumEntryData> processImage(UUID albumId, String filename);
}
