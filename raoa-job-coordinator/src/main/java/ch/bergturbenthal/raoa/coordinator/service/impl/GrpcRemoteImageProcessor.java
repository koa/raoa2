package ch.bergturbenthal.raoa.coordinator.service.impl;

import ch.bergturbenthal.raoa.coordinator.service.RemoteImageProcessor;
import ch.bergturbenthal.raoa.libs.model.elasticsearch.AlbumEntryData;
import ch.bergturbenthal.raoa.libs.model.kafka.ProcessImageRequest;
import ch.bergturbenthal.raoa.processing.grpc.ImageProcessing;
import ch.bergturbenthal.raoa.processing.grpc.ProcessImageServiceGrpc;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class GrpcRemoteImageProcessor implements RemoteImageProcessor {
  public static final Predicate<Double> DOUBLE_GT_ZERO = v -> v > 0;
  public static final Predicate<Integer> INTEGER_GT_ZERO = v -> v > 0;
  private final MeterRegistry meterRegistry;

  private final ProcessImageServiceGrpc.ProcessImageServiceStub processImageServiceStub;

  public GrpcRemoteImageProcessor(
      final MeterRegistry meterRegistry,
      final ProcessImageServiceGrpc.ProcessImageServiceStub processImageServiceStub) {
    this.meterRegistry = meterRegistry;
    this.processImageServiceStub = processImageServiceStub;
  }

  @Override
  public Mono<AlbumEntryData> processImage(final ObjectId fileId, final ProcessImageRequest data) {
    // log.info("Process: " + data);
    final UUID albumId = data.getAlbumId();
    final ImageProcessing.ProcessImageRequest request =
        ImageProcessing.ProcessImageRequest.newBuilder()
            .setAlbumId(convertUUID(albumId))
            .setFilename(data.getFilename())
            .build();
    return Mono.<ImageProcessing.AlbumEntryMetadata>create(
            sink -> {
              sink.onRequest(
                  count -> {
                    if (count > 0) {
                      long startTime = System.nanoTime();
                      processImageServiceStub.processImage(
                          request,
                          new StreamObserver<>() {
                            private boolean done = false;

                            @Override
                            public void onNext(final ImageProcessing.AlbumEntryMetadata value) {
                              meterRegistry
                                  .timer("image-processor.call", "response", "data")
                                  .record(Duration.ofNanos(System.nanoTime() - startTime));
                              sink.success(value);
                              done = true;
                            }

                            @Override
                            public void onError(final Throwable t) {
                              meterRegistry
                                  .timer("image-processor.call", "response", "exception")
                                  .record(Duration.ofNanos(System.nanoTime() - startTime));
                              sink.error(t);
                              done = true;
                            }

                            @Override
                            public void onCompleted() {
                              if (!done) {
                                meterRegistry
                                    .timer("image-processor.call", "response", "empty")
                                    .record(Duration.ofNanos(System.nanoTime() - startTime));
                                sink.success();
                              }
                            }
                          });
                    }
                  });
            })
        // .log(albumId + "; " + request.getFilename())
        .map(
            response -> {
              final AlbumEntryData.AlbumEntryDataBuilder builder =
                  AlbumEntryData.builder()
                      .albumId(data.getAlbumId())
                      .cameraManufacturer(response.getCameraManufacturer())
                      .cameraModel(response.getCameraModel())
                      .captureCoordinates(convertCoordinates(response.getCaptureCoordinates()))
                      .contentType(response.getContentType())
                      .createTime(convertTimestamp(response.getCreateTime()))
                      .description(response.getDescription())
                      .entryId(convertObjectId(response.getObjectId()))
                      .filename(response.getFilename());

              Optional.of(response.getExposureTime())
                  .filter(DOUBLE_GT_ZERO)
                  .ifPresent(builder::exposureTime);
              Optional.of(response.getFNumber()).filter(DOUBLE_GT_ZERO).ifPresent(builder::fNumber);
              Optional.of(response.getFocalLength())
                  .filter(DOUBLE_GT_ZERO)
                  .ifPresent(builder::focalLength);
              Optional.of(response.getFocalLength35())
                  .filter(DOUBLE_GT_ZERO)
                  .ifPresent(builder::focalLength35);
              Optional.of(response.getWidth()).filter(INTEGER_GT_ZERO).ifPresent(builder::width);
              Optional.of(response.getHeight()).filter(INTEGER_GT_ZERO).ifPresent(builder::height);
              Optional.of(response.getTargetWidth())
                  .filter(INTEGER_GT_ZERO)
                  .ifPresent(builder::targetWidth);
              Optional.of(response.getTargetHeight())
                  .filter(INTEGER_GT_ZERO)
                  .ifPresent(builder::targetHeight);
              Optional.of(response.getIsoSpeedRatings())
                  .filter(DOUBLE_GT_ZERO)
                  .map(Double::intValue)
                  .ifPresent(builder::isoSpeedRatings);
              Optional.of(response.getRating()).filter(INTEGER_GT_ZERO).ifPresent(builder::rating);
              builder.keywords(new HashSet<>(response.getKeywordList()));

              return builder.build();
            })
        .retryBackoff(10, Duration.ofSeconds(2), Duration.ofMinutes(1));
  }

  private ObjectId convertObjectId(final ImageProcessing.GitObjectId objectId) {
    if (objectId == null) return null;
    return ObjectId.fromRaw(objectId.getId().toByteArray());
  }

  private Instant convertTimestamp(final Timestamp createTime) {
    if (createTime == null) return null;
    return Instant.ofEpochSecond(createTime.getSeconds(), createTime.getNanos());
  }

  private GeoPoint convertCoordinates(final ImageProcessing.GeoCoordinates captureCoordinates) {
    if (captureCoordinates == null) return null;
    return new GeoPoint(captureCoordinates.getLatitude(), captureCoordinates.getLongitude());
  }

  @NotNull
  public ImageProcessing.UUID convertUUID(final UUID albumId) {
    final ByteBuffer buffer = ByteBuffer.allocate(128 / 8);
    buffer.putLong(albumId.getMostSignificantBits());
    buffer.putLong(albumId.getLeastSignificantBits());
    buffer.rewind();
    final ByteString bytes = ByteString.copyFrom(buffer);
    return ImageProcessing.UUID.newBuilder().setId(bytes).build();
  }
}
