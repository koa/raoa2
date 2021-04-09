package ch.bergturbenthal.raoa.processor.image.interfaces;

import ch.bergturbenthal.raoa.libs.StreamObserverReactiveHelper;
import ch.bergturbenthal.raoa.processing.grpc.ImageProcessing;
import ch.bergturbenthal.raoa.processing.grpc.ProcessImageServiceGrpc;
import ch.bergturbenthal.raoa.processor.image.service.ImageProcessor;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.lognet.springboot.grpc.GRpcService;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;

@Slf4j
@GRpcService
public class GrpcController extends ProcessImageServiceGrpc.ProcessImageServiceImplBase {
  private final ImageProcessor imageProcessor;

  public GrpcController(final ImageProcessor imageProcessor) {
    this.imageProcessor = imageProcessor;
  }

  @Override
  public void processImage(
      final ImageProcessing.ProcessImageRequest request,
      final StreamObserver<ImageProcessing.AlbumEntryMetadata> responseObserver) {
    final ByteBuffer byteBuffer = request.getAlbumId().getId().asReadOnlyByteBuffer();
    final long msb = byteBuffer.getLong();
    final long lsb = byteBuffer.getLong();
    final UUID albumId = new UUID(msb, lsb);
    final String filename = request.getFilename();
    // log.info("Take: " + albumId + "; " + filename);
    imageProcessor
        .processImage(albumId, filename)
        .map(
            data -> {
              final ImageProcessing.AlbumEntryMetadata.Builder builder =
                  ImageProcessing.AlbumEntryMetadata.newBuilder();
              builder.setObjectId(objectId2Grpc(data.getEntryId()));
              Optional.ofNullable(data.getWidth()).ifPresent(builder::setWidth);
              Optional.ofNullable(data.getHeight()).ifPresent(builder::setHeight);
              Optional.ofNullable(data.getTargetWidth()).ifPresent(builder::setTargetWidth);
              Optional.ofNullable(data.getTargetHeight()).ifPresent(builder::setTargetHeight);
              Optional.ofNullable(data.getFilename()).ifPresent(builder::setFilename);
              Optional.ofNullable(data.getCreateTime())
                  .map(this::convertTime)
                  .ifPresent(builder::setCreateTime);
              Optional.ofNullable(data.getCameraModel()).ifPresent(builder::setCameraModel);
              Optional.ofNullable(data.getCameraManufacturer())
                  .ifPresent(builder::setCameraManufacturer);
              Optional.ofNullable(data.getFocalLength()).ifPresent(builder::setFocalLength);
              Optional.ofNullable(data.getFocalLength35()).ifPresent(builder::setFocalLength35);
              Optional.ofNullable(data.getFNumber()).ifPresent(builder::setFNumber);
              Optional.ofNullable(data.getExposureTime()).ifPresent(builder::setExposureTime);
              Optional.ofNullable(data.getIsoSpeedRatings()).ifPresent(builder::setIsoSpeedRatings);
              Optional.ofNullable(data.getContentType()).ifPresent(builder::setContentType);
              Optional.ofNullable(data.getKeywords())
                  .ifPresent(keywords -> keywords.forEach(builder::addKeyword));
              Optional.ofNullable(data.getDescription()).ifPresent(builder::setDescription);
              Optional.ofNullable(data.getRating()).ifPresent(builder::setRating);
              Optional.ofNullable(data.getCaptureCoordinates())
                  .map(this::convertCoordinates)
                  .ifPresent(builder::setCaptureCoordinates);
              Optional.ofNullable(data.getXmpFileId())
                  .map(this::objectId2Grpc)
                  .ifPresent(builder::setXmpMetadataId);
              return builder.build();
            })
        // .log(filename)
        .subscribe(StreamObserverReactiveHelper.toSubscriber(responseObserver));
  }

  private ImageProcessing.GeoCoordinates convertCoordinates(final GeoPoint geoPoint) {
    return ImageProcessing.GeoCoordinates.newBuilder()
        .setLatitude(geoPoint.getLat())
        .setLongitude(geoPoint.getLon())
        .build();
  }

  private Timestamp convertTime(final Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  public ImageProcessing.GitObjectId objectId2Grpc(final ObjectId entryId) {
    final ByteBuffer buffer = ByteBuffer.allocate(20);
    entryId.copyRawTo(buffer);
    buffer.rewind();
    return ImageProcessing.GitObjectId.newBuilder().setId(ByteString.copyFrom(buffer)).build();
  }
}
