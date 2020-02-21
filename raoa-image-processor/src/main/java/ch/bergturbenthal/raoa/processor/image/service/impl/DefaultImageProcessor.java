package ch.bergturbenthal.raoa.processor.image.service.impl;

import ch.bergturbenthal.raoa.elastic.model.AlbumEntryData;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.AsyncService;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.ThumbnailFilenameService;
import ch.bergturbenthal.raoa.libs.service.impl.XmpWrapper;
import ch.bergturbenthal.raoa.processor.image.service.ImageProcessor;
import com.adobe.xmp.XMPMeta;
import com.adobe.xmp.XMPMetaFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.imaging.ImageWriteException;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputField;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.*;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectStream;
import org.eclipse.jgit.treewalk.filter.OrTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.function.TupleUtils;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

@Slf4j
@Service
public class DefaultImageProcessor implements ImageProcessor {
  private static final Pattern NUMBER_PATTERN = Pattern.compile("[0-9.]+");
  private static final Set<String> RAW_ENDINGS = Set.of(".nef", ".dng", ".cr2", ".crw", ".cr3");
  private final AlbumList albumList;
  private final AsyncService asyncService;
  private final ThumbnailFilenameService thumbnailFilenameService;
  private final MeterRegistry meterRegistry;
  private final AutoDetectParser parser;

  public DefaultImageProcessor(
      final AlbumList albumList,
      final AsyncService asyncService,
      final ThumbnailFilenameService thumbnailFilenameService,
      final MeterRegistry meterRegistry) {
    this.albumList = albumList;
    this.asyncService = asyncService;
    this.thumbnailFilenameService = thumbnailFilenameService;
    this.meterRegistry = meterRegistry;
    parser = new AutoDetectParser();
  }

  private static Optional<Integer> extractTargetWidth(final Metadata m) {
    if (Optional.ofNullable(m.get(TIFF.ORIENTATION)).map(Integer::valueOf).orElse(0) <= 4) {
      return Optional.ofNullable(m.getInt(TIFF.IMAGE_WIDTH));
    } else {
      return Optional.ofNullable(m.getInt(TIFF.IMAGE_LENGTH));
    }
  }

  private static Optional<Integer> extractTargetHeight(final Metadata m) {
    if (Optional.ofNullable(m.get(TIFF.ORIENTATION)).map(Integer::valueOf).orElse(0) <= 4) {
      return Optional.ofNullable(m.getInt(TIFF.IMAGE_LENGTH));
    } else {
      return Optional.ofNullable(m.getInt(TIFF.IMAGE_WIDTH));
    }
  }

  private static Optional<Integer> extractInteger(final Metadata m, final Property property) {
    return Optional.ofNullable(m.getInt(property));
  }

  private static Optional<String> extractString(final Metadata m, final Property property) {
    return Optional.ofNullable(m.get(property));
  }

  private static Optional<Instant> extractInstant(final Metadata m, final Property property) {
    return Optional.ofNullable(m.getDate(property)).map(Date::toInstant);
  }

  private static Optional<Double> extractDouble(final Metadata m, final Property property) {
    return Optional.ofNullable(m.get(property)).map(Double::valueOf);
  }

  private static AlbumEntryData createAlbumEntry(
      final UUID albumId,
      final ObjectId fileId,
      final String filename,
      final Metadata metadata,
      final Optional<XMPMeta> xmpMeta) {
    final AlbumEntryData.AlbumEntryDataBuilder albumEntryDataBuilder =
        AlbumEntryData.builder().filename(filename).entryId(fileId).albumId(albumId);
    extractInstant(metadata, TikaCoreProperties.CREATED)
        .ifPresent(albumEntryDataBuilder::createTime);
    extractTargetWidth(metadata).ifPresent(albumEntryDataBuilder::targetWidth);
    extractTargetHeight(metadata).ifPresent(albumEntryDataBuilder::targetHeight);
    extractInteger(metadata, TIFF.IMAGE_WIDTH).ifPresent(albumEntryDataBuilder::width);
    extractInteger(metadata, TIFF.IMAGE_LENGTH).ifPresent(albumEntryDataBuilder::height);
    extractString(metadata, TIFF.EQUIPMENT_MODEL).ifPresent(albumEntryDataBuilder::cameraModel);
    extractString(metadata, TIFF.EQUIPMENT_MAKE)
        .ifPresent(albumEntryDataBuilder::cameraManufacturer);
    extractDouble(metadata, TIFF.FOCAL_LENGTH).ifPresent(albumEntryDataBuilder::focalLength);
    extractDouble(metadata, TIFF.F_NUMBER).ifPresent(albumEntryDataBuilder::fNumber);
    extractDouble(metadata, TIFF.EXPOSURE_TIME).ifPresent(albumEntryDataBuilder::exposureTime);
    extractInteger(metadata, TIFF.ISO_SPEED_RATINGS)
        .or(() -> extractInteger(metadata, Property.internalInteger("ISO Speed Ratings")))
        .ifPresent(albumEntryDataBuilder::isoSpeedRatings);

    extractString(metadata, Property.internalInteger("Focal Length 35"))
        .map(v -> v.split(" ")[0])
        .map(String::trim)
        .filter(v -> NUMBER_PATTERN.matcher(v).matches())
        .map(Double::valueOf)
        .ifPresent(albumEntryDataBuilder::focalLength35);

    extractString(metadata, Property.externalText(HttpHeaders.CONTENT_TYPE))
        .ifPresent(albumEntryDataBuilder::contentType);
    final Optional<Double> lat = extractDouble(metadata, TikaCoreProperties.LATITUDE);
    final Optional<Double> lon = extractDouble(metadata, TikaCoreProperties.LONGITUDE);
    if (lat.isPresent() && lon.isPresent()) {
      albumEntryDataBuilder.captureCoordinates(new GeoPoint(lat.get(), lon.get()));
    }
    xmpMeta
        .map(XmpWrapper::new)
        .ifPresent(
            xmpWrapper -> {
              albumEntryDataBuilder.description(xmpWrapper.readDescription());
              albumEntryDataBuilder.rating(xmpWrapper.readRating());
              albumEntryDataBuilder.keywords(new HashSet<>(xmpWrapper.readKeywords()));
            });
    return albumEntryDataBuilder.build();
  }

  private static TiffOutputSet copy(final TiffOutputSet in) throws ImageWriteException {
    final TiffOutputSet out = new TiffOutputSet(in.byteOrder);
    for (TiffOutputDirectory inDir : in.getDirectories()) {
      final TiffOutputDirectory outDir = new TiffOutputDirectory(inDir.type, in.byteOrder);
      out.addDirectory(outDir);
      for (TiffOutputField inField : inDir.getFields()) {
        outDir.add(inField);
      }
    }
    return out;
  }

  public static BufferedImage loadImage(File file) throws IOException {
    final String lowerFilename = file.getName().toLowerCase();
    if (lowerFilename.length() > 4) {
      final String ending = lowerFilename.substring(lowerFilename.length() - 4);
      if (RAW_ENDINGS.contains(ending)) {
        final Process process =
            Runtime.getRuntime().exec(new String[] {"dcraw", "-c", file.getAbsolutePath()});
        final InputStream inputStream = process.getInputStream();
        return ImageIO.read(inputStream);
      }
    }
    return ImageIO.read(file);
  }

  @Override
  @NotNull
  public Mono<AlbumEntryData> processImage(final UUID albumId, final String filename) {
    AtomicInteger fileCount = new AtomicInteger(0);
    AtomicLong byteWriteCount = new AtomicLong(0);
    AtomicLong byteReadCount = new AtomicLong(0);
    final long startTime = System.nanoTime();
    final String xmpFilename = filename + ".xmp";
    TreeFilter interestingFileFilter =
        OrTreeFilter.create(
            new TreeFilter[] {PathFilter.create(filename), PathFilter.create(xmpFilename)});
    final Mono<GitAccess> album = albumList.getAlbum(albumId)
        //                               .log("Album: " + albumId)
        ;

    final Mono<AlbumEntryData> albumEntryDataMono1 =
        album.flatMap(
            a -> {
              final Mono<Tuple2<ObjectId, Optional<ObjectId>>> foundFilesMono =
                  a.listFiles(interestingFileFilter)
                      .collectMap(
                          GitAccess.GitFileEntry::getNameString, GitAccess.GitFileEntry::getFileId)
                      .map(
                          (Map<String, ObjectId> m) -> {
                            final ObjectId t1 = m.get(filename);
                            final ObjectId value = m.get(xmpFilename);
                            if (t1 == null) {
                              log.warn("File not found: " + filename);
                            }
                            return Tuples.of(Optional.ofNullable(t1), Optional.ofNullable(value));
                          })
                      .filter(t -> t.getT1().isPresent())
                      .map(t -> t.mapT1(Optional::get))
                  // .log("found files " + filename)
                  ;

              final Mono<Tuple3<ObjectId, File, Optional<XMPMeta>>> tmp =
                  foundFilesMono.flatMap(
                      t ->
                          Mono.zip(
                                  a.readObject(t.getT1())
                                      .flatMap(
                                          loader1 ->
                                              asyncService.asyncMono(
                                                  () -> {
                                                    final File tempFile1 =
                                                        File.createTempFile("tmp", ".jpg");
                                                    {
                                                      @Cleanup
                                                      final FileOutputStream fileOutputStream1 =
                                                          new FileOutputStream(tempFile1);
                                                      loader1.copyTo(fileOutputStream1);
                                                    }
                                                    // log.info("File: " + tempFile1);
                                                    // log.info("Exists: " + tempFile1.exists());
                                                    // log.info("Size: " + tempFile1.length());
                                                    byteReadCount.addAndGet(tempFile1.length());
                                                    return tempFile1;
                                                  }))
                                  //    .log("loaded " + filename)
                                  ,
                                  t.getT2()
                                      .map(
                                          fileId ->
                                              a.readObject(fileId)
                                                  .flatMap(
                                                      loader ->
                                                          asyncService.asyncMono(
                                                              () -> {
                                                                try (final ObjectStream stream =
                                                                    loader.openStream()) {
                                                                  byteReadCount.addAndGet(
                                                                      stream.getSize());
                                                                  return XMPMetaFactory.parse(
                                                                      stream);
                                                                }
                                                              }))
                                                  .map(Optional::of)
                                                  .onErrorReturn(Optional.empty()))
                                      .orElse(Mono.just(Optional.empty()))
                                  //            .log("xmp: " + filename)
                                  )
                              .map(x -> Tuples.of(t.getT1(), x.getT1(), x.getT2())));
              return tmp.flatMap(
                  TupleUtils.function(
                      (entryId, file, optionalXMPMeta) -> {
                        final Mono<Metadata> metadataMono =
                            asyncService
                                .asyncMono(
                                    () -> {
                                      // log.info("File " + file + " exists: " + file.exists());
                                      BodyContentHandler handler = new BodyContentHandler();
                                      Metadata metadata = new Metadata();

                                      final Path path = file.toPath();

                                      // log.info("Path " + path + " exists: " +
                                      // Files.exists(path));
                                      @Cleanup
                                      final TikaInputStream inputStream = TikaInputStream.get(path);
                                      parser.parse(inputStream, handler, metadata);
                                      return metadata;
                                    })
                                // .log("metadata: " + filename)
                                .cache();
                        final Mono<Optional<TiffOutputSet>> overrideableMetadata =
                            asyncService
                                .asyncMono(() -> Imaging.getMetadata(file))
                                .cast(JpegImageMetadata.class)
                                .map(m -> Optional.ofNullable(m.getExif()))
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .cache()
                                .map(
                                    m -> {
                                      try {
                                        return m.getOutputSet();
                                      } catch (ImageWriteException e) {
                                        throw new RuntimeException(e);
                                      }
                                    })
                                .map(Optional::of)
                                .onErrorReturn(Optional.empty())
                                .defaultIfEmpty(Optional.empty());
                        final Mono<BufferedImage> inputImage =
                            asyncService.asyncMono(() -> loadImage(file)).cache();
                        final Mono<AlbumEntryData> albumEntryDataMono =
                            Mono.zip(metadataMono, inputImage, overrideableMetadata)
                                // .log("zip: " + filename)
                                .flatMap(
                                    TupleUtils.function(
                                        (Metadata metadata,
                                            BufferedImage image,
                                            Optional<TiffOutputSet> optionalTiffOutputSet) ->
                                            createThumbnails(
                                                albumId,
                                                filename,
                                                fileCount,
                                                byteWriteCount,
                                                entryId,
                                                optionalXMPMeta,
                                                metadataMono,
                                                metadata,
                                                image,
                                                optionalTiffOutputSet)))
                            // .log("result: " + filename)
                            ;
                        return albumEntryDataMono.doFinally(
                            signal -> {
                              // log.info("delete finally <" + filename + ">: " + file);
                              file.delete();
                            });
                      }));
            });
    return albumEntryDataMono1.doFinally(
        signal -> {
          final long endTime = System.nanoTime();
          final Tags tags = Tags.of("result", signal.name());
          meterRegistry
              .timer("image.processing", tags)
              .record(Duration.ofNanos(endTime - startTime));
          meterRegistry.counter("image.filewritten", tags).increment(fileCount.get());
          meterRegistry.counter("image.byteread", tags).increment(byteReadCount.get());
          meterRegistry.counter("image.bytewritten", tags).increment(byteWriteCount.get());
        })
    // .log("process " + filename)
    ;
  }

  @NotNull
  public Mono<AlbumEntryData> createThumbnails(
      final UUID albumId,
      final String filename,
      final AtomicInteger fileCount,
      final AtomicLong byteWriteCount,
      final ObjectId entryId,
      final Optional<XMPMeta> optionalXMPMeta,
      final Mono<Metadata> metadataMono,
      final Metadata metadata,
      final BufferedImage image,
      final Optional<TiffOutputSet> optionalTiffOutputSet) {
    final int orientation =
        Optional.ofNullable(metadata.get(TIFF.ORIENTATION)).map(Integer::parseInt).orElse(1);
    final int width = image.getWidth();
    final int height = image.getHeight();
    int maxLength = Math.max(width, height);
    return Flux.fromStream(thumbnailFilenameService.listThumbnailsOf(albumId, entryId))
        .flatMap(
            (ThumbnailFilenameService.FileAndScale fileAndScale) -> {
              int size = fileAndScale.getSize();
              File targetFile = fileAndScale.getFile();
              if (targetFile.exists()) {
                // log.info("Scale " + filename + " to " + size + " already exists");
                return Mono.<Boolean>just(true);
              }
              return asyncService.<Boolean>asyncMono(
                  () -> {
                    // log.info("Scale " + filename + " to " + size);
                    final File parentDir = targetFile.getParentFile();
                    if (!parentDir.exists()) parentDir.mkdirs();
                    final File tempFile = new File(parentDir, UUID.randomUUID().toString());
                    try {
                      AffineTransform t = new AffineTransform();
                      final boolean flip;
                      double scale = size * 1.0 / maxLength;
                      switch (orientation) {
                        default:
                        case 1:
                          flip = false;
                          break;
                        case 2: // Flip X
                          flip = false;
                          t.scale(-1.0, 1.0);
                          t.translate(-width * scale, 0);
                          break;
                        case 3: // PI rotation
                          flip = false;
                          t.translate(width * scale, height * scale);
                          t.quadrantRotate(2);
                          break;
                        case 4: // Flip Y
                          flip = false;
                          t.scale(1.0, -1.0);
                          t.translate(0, -height * scale);
                          break;
                        case 5: // - PI/2 and Flip X
                          flip = true;
                          t.quadrantRotate(3);
                          t.scale(-1.0, 1.0);
                          break;
                        case 6: // -PI/2 and -width
                          flip = true;
                          t.translate(height * scale, 0);
                          t.quadrantRotate(1);
                          break;
                        case 7: // PI/2 and Flip
                          flip = true;
                          t.scale(-1.0, 1.0);
                          t.translate(-height * scale, 0);
                          t.translate(0, width * scale);
                          t.quadrantRotate(3);
                          break;
                        case 8: // PI / 2
                          flip = true;
                          t.translate(0, width * scale);
                          t.quadrantRotate(3);
                          break;
                      }
                      t.scale(scale, scale);
                      int targetWith;
                      int targetHeight;
                      if (flip) {
                        targetWith = (int) (height * scale);
                        targetHeight = (int) (width * scale);
                      } else {
                        targetWith = (int) (width * scale);
                        targetHeight = (int) (height * scale);
                      }
                      BufferedImage targetImage =
                          new BufferedImage(targetWith, targetHeight, BufferedImage.TYPE_INT_RGB);
                      Graphics2D graphics = targetImage.createGraphics();
                      graphics.setTransform(t);
                      graphics.drawImage(image, 0, 0, null);
                      graphics.dispose();
                      // log.info("write temp: " +
                      // tempFile);

                      if (optionalTiffOutputSet.isPresent()) {
                        final TiffOutputSet tiffOutputSet = copy(optionalTiffOutputSet.get());

                        final TiffOutputDirectory tiffOutputDirectory =
                            tiffOutputSet.getRootDirectory();
                        if (tiffOutputDirectory != null) {
                          tiffOutputDirectory.removeField(TiffTagConstants.TIFF_TAG_ORIENTATION);
                          tiffOutputDirectory.add(
                              TiffTagConstants.TIFF_TAG_ORIENTATION,
                              (short) TiffTagConstants.ORIENTATION_VALUE_HORIZONTAL_NORMAL);
                        }

                        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        final boolean writeOk = ImageIO.write(targetImage, "jpg", baos);
                        if (writeOk) {
                          {
                            @Cleanup
                            final OutputStream os =
                                new BufferedOutputStream(
                                    new FileOutputStream(tempFile), 254 * 1024);
                            new ExifRewriter()
                                .updateExifMetadataLossless(baos.toByteArray(), os, tiffOutputSet);
                          }
                          tempFile.renameTo(targetFile);
                          fileCount.incrementAndGet();
                        }
                        return writeOk;
                      } else {
                        final boolean writeOk = ImageIO.write(targetImage, "jpg", tempFile);
                        if (writeOk) {
                          tempFile.renameTo(targetFile);
                          fileCount.incrementAndGet();
                        }
                        return writeOk;
                      }
                    } finally {
                      // log.info("delete " + tempFile);
                      tempFile.delete();
                      byteWriteCount.addAndGet(targetFile.length());
                      // log.info("written: " + targetFile);
                    }
                  })
              // .log("thmb " + size)
              ;
            })
        .collect(() -> new AtomicBoolean(true), (ret, value) -> ret.compareAndExchange(true, value))
        .map(AtomicBoolean::get)
        // .log("tmb: " + filename)
        .filter(b -> b)
        .flatMap(
            ok ->
                metadataMono.map(
                    fileMeta ->
                        createAlbumEntry(albumId, entryId, filename, fileMeta, optionalXMPMeta)));
  }
}
