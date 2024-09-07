package ch.bergturbenthal.raoa.processor.media.service.impl;

import ch.bergturbenthal.raoa.elastic.model.AlbumEntryData;
import ch.bergturbenthal.raoa.elastic.repository.AlbumDataEntryRepository;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.AsyncService;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.libs.service.ThumbnailFilenameService;
import ch.bergturbenthal.raoa.libs.service.impl.XmpWrapper;
import ch.bergturbenthal.raoa.processor.media.properties.JobProperties;
import ch.bergturbenthal.raoa.processor.media.service.Processor;
import com.adobe.internal.xmp.XMPMeta;
import com.adobe.internal.xmp.XMPMetaFactory;
import com.drew.lang.Charsets;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import lombok.Cleanup;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.imaging.ImageReadException;
import org.apache.commons.imaging.ImageWriteException;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputField;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Property;
import org.apache.tika.metadata.TIFF;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.metadata.XMPDM;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectStream;
import org.eclipse.jgit.treewalk.filter.OrTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

@Slf4j
@Service
public class DefaultProcessor implements Processor {
  private static final Pattern NUMBER_PATTERN = Pattern.compile("[0-9.]+");
  private static final Set<String> RAW_ENDINGS = Set.of(".nef", ".dng", ".cr2", ".crw", ".cr3");
  private static final boolean HAS_DCRAW = hasDcraw();
  private static final Object dcrawLock = new Object();

  private final JobProperties jobProperties;
  private final AlbumList albumList;
  private final AsyncService asyncService;
  private final AutoDetectParser parser;
  private final ThumbnailFilenameService thumbnailFilenameService;
  private final AlbumDataEntryRepository albumDataEntryRepository;

  public DefaultProcessor(
      final JobProperties jobProperties,
      final AlbumList albumList,
      final AsyncService asyncService,
      final ThumbnailFilenameService thumbnailFilenameService,
      final AlbumDataEntryRepository albumDataEntryRepository) {
    this.jobProperties = jobProperties;
    this.albumList = albumList;
    this.asyncService = asyncService;
    this.thumbnailFilenameService = thumbnailFilenameService;
    this.albumDataEntryRepository = albumDataEntryRepository;
    parser = new AutoDetectParser();
  }

  private static boolean hasDcraw() {
    try {
      final Process process = Runtime.getRuntime().exec(new String[] {"dcraw"});
      process.waitFor();
      return true;
    } catch (IOException | InterruptedException ex) {
      log.warn("dcraw not found", ex);
      return false;
    }
  }

  private static AlbumEntryData createAlbumEntry(
      final UUID albumId,
      final ObjectId fileId,
      final String filename,
      final Metadata metadata,
      final Optional<ObjectId> xmpFileId,
      final Optional<XMPMeta> xmpMeta) {
    final AlbumEntryData.AlbumEntryDataBuilder albumEntryDataBuilder =
        AlbumEntryData.builder().filename(filename).entryId(fileId).albumId(albumId);
    xmpFileId.ifPresent(albumEntryDataBuilder::xmpFileId);
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

  public static Tuple2<BufferedImage, Boolean> loadImage(File file) throws IOException {
    final String lowerFilename = file.getName().toLowerCase();
    if (lowerFilename.length() > 4) {
      final String ending = lowerFilename.substring(lowerFilename.length() - 4);
      if (HAS_DCRAW && RAW_ENDINGS.contains(ending)) {
        synchronized (dcrawLock) {
          final Process process =
              Runtime.getRuntime().exec(new String[] {"dcraw", "-c", file.getAbsolutePath()});
          final InputStream inputStream = process.getInputStream();
          return Tuples.of(ImageIO.read(inputStream), true);
        }
      }
    }
    return Tuples.of(ImageIO.read(file), false);
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

  @Override
  public boolean run() {
    final UUID albumId = jobProperties.getRepository();
    final Set<String> jobFiles = jobProperties.getFiles();
    return Boolean.TRUE.equals(
        albumList
            .getAlbum(albumId)
            .flatMap(
                ga ->
                    ga.listFiles(
                            OrTreeFilter.create(
                                jobFiles.stream()
                                    .map(s -> URLDecoder.decode(s, Charsets.UTF_8))
                                    .map(filename1 -> filename1 + ".xmp")
                                    .map(PathFilter::create)
                                    .collect(Collectors.toUnmodifiableList())))
                        .collectMap(GitAccess.GitFileEntry::getNameString, Function.identity())
                        .doOnNext(
                            metadatafiles -> {
                              log.info(
                                  "found {} metadata files for {}",
                                  metadatafiles.size(),
                                  jobFiles.size());
                            })
                        .flatMap(
                            metadataFiles ->
                                ga.listFiles(
                                        OrTreeFilter.create(
                                            jobFiles.stream()
                                                .map(s -> URLDecoder.decode(s, Charsets.UTF_8))
                                                .map(PathFilter::create)
                                                .collect(Collectors.toUnmodifiableList())))
                                    .flatMap(
                                        contentFile -> {
                                          final String filename = contentFile.getNameString();
                                          final String metadataFilename = filename + ".xml";
                                          return Mono.zip(
                                                  readFileEntryToTemp(ga, contentFile)
                                                      .flatMap(
                                                          entry ->
                                                              asyncService
                                                                  .asyncMono(
                                                                      () -> {
                                                                        BodyContentHandler handler =
                                                                            new BodyContentHandler();
                                                                        Metadata metadata =
                                                                            new Metadata();

                                                                        final Path path =
                                                                            entry.getT2().toPath();
                                                                        try (final TikaInputStream
                                                                            inputStream =
                                                                                TikaInputStream.get(
                                                                                    path)) {
                                                                          parser.parse(
                                                                              inputStream,
                                                                              handler,
                                                                              metadata);
                                                                          return metadata;
                                                                        }
                                                                      })
                                                                  .map(
                                                                      metadata ->
                                                                          Tuples.of(
                                                                              entry.getT1(),
                                                                              entry.getT2(),
                                                                              metadata))),
                                                  Mono.justOrEmpty(
                                                          metadataFiles.get(metadataFilename))
                                                      .map(GitAccess.GitFileEntry::getFileId)
                                                      .flatMap(
                                                          entry ->
                                                              ga.readObject(entry)
                                                                  .flatMap(
                                                                      loader ->
                                                                          asyncService.asyncMono(
                                                                              () -> {
                                                                                try (final
                                                                                ObjectStream
                                                                                    stream =
                                                                                        loader
                                                                                            .openStream()) {
                                                                                  return XMPMetaFactory
                                                                                      .parse(
                                                                                          stream);
                                                                                }
                                                                              }))
                                                                  .map(
                                                                      meta ->
                                                                          Tuples.of(
                                                                              Optional.of(entry),
                                                                              Optional.of(meta)))
                                                                  .onErrorResume(
                                                                      ex -> {
                                                                        log.warn(
                                                                            "Cannot read file "
                                                                                + metadataFilename,
                                                                            ex);
                                                                        return Mono.empty();
                                                                      }))
                                                      .defaultIfEmpty(
                                                          Tuples.of(
                                                              Optional.empty(), Optional.empty())))
                                              .map(
                                                  t ->
                                                      Tuples.of(
                                                          t.getT1().getT1(),
                                                          t.getT1().getT2(),
                                                          t.getT1().getT3(),
                                                          t.getT2().getT1(),
                                                          t.getT2().getT2()))
                                              .filterWhen(
                                                  params -> {
                                                    final Metadata metadata = params.getT3();
                                                    final ObjectId imageFileId =
                                                        params.getT1().getFileId();
                                                    final String contentType =
                                                        metadata.get(HttpHeaders.CONTENT_TYPE);
                                                    final Stream<
                                                            ThumbnailFilenameService.FileAndScale>
                                                        fileAndScaleStream =
                                                            thumbnailFilenameService
                                                                .listThumbnailsOf(
                                                                    albumId, imageFileId);
                                                    if (contentType.startsWith("image")) {
                                                      final List<
                                                              ThumbnailFilenameService.FileAndScale>
                                                          missingThumbnails =
                                                              fileAndScaleStream
                                                                  .filter(
                                                                      fas ->
                                                                          !fas.getFile().exists())
                                                                  .collect(Collectors.toList());
                                                      if (!missingThumbnails.isEmpty()) {
                                                        log.info(
                                                            "Scaling "
                                                                + filename
                                                                + " to sizes "
                                                                + missingThumbnails.stream()
                                                                    .map(
                                                                        ThumbnailFilenameService
                                                                                .FileAndScale
                                                                            ::getSize)
                                                                    .map(Object::toString)
                                                                    .collect(
                                                                        Collectors.joining(", ")));
                                                        return asyncService.asyncMono(
                                                            () -> {
                                                              final File mediaFile = params.getT2();

                                                              final ImageMetadata imageMetadata =
                                                                  Imaging.getMetadata(mediaFile);

                                                              final Optional<TiffOutputSet>
                                                                  tiffOutputSet;
                                                              if (imageMetadata
                                                                  instanceof JpegImageMetadata) {
                                                                tiffOutputSet =
                                                                    Optional.ofNullable(
                                                                            ((JpegImageMetadata)
                                                                                    imageMetadata)
                                                                                .getExif())
                                                                        .map(
                                                                            m -> {
                                                                              try {
                                                                                return m
                                                                                    .getOutputSet();
                                                                              } catch (
                                                                                  ImageWriteException
                                                                                      e) {
                                                                                throw new RuntimeException(
                                                                                    e);
                                                                              }
                                                                            });
                                                                /*} else if (imageMetadata
                                                                                                                      instanceof TiffImageMetadata) {
                                                                                                                    tiffOutputSet =
                                                                                                                        Optional.ofNullable(
                                                                                                                            ((TiffImageMetadata) imageMetadata)
                                                                                                                                .getOutputSet());
                                                                */
                                                              } else
                                                                tiffOutputSet = Optional.empty();

                                                              final Tuple2<BufferedImage, Boolean>
                                                                  loadedImage =
                                                                      loadImage(mediaFile);
                                                              final int orientation =
                                                                  loadedImage.getT2()
                                                                      ? 1
                                                                      : Optional.ofNullable(
                                                                              metadata.get(
                                                                                  TIFF.ORIENTATION))
                                                                          .map(Integer::parseInt)
                                                                          .orElse(1);
                                                              return createImageThumbnails(
                                                                  albumId,
                                                                  imageFileId,
                                                                  loadedImage.getT1(),
                                                                  tiffOutputSet,
                                                                  missingThumbnails,
                                                                  orientation);
                                                            });
                                                      }
                                                    } else if (contentType.startsWith("video")) {
                                                      final List<
                                                              ThumbnailFilenameService.FileAndScale>
                                                          missingThumbnails =
                                                              fileAndScaleStream
                                                                  .filter(
                                                                      fas ->
                                                                          !fas.getFile().exists()
                                                                              || !fas.getVideoFile()
                                                                                  .exists())
                                                                  .collect(Collectors.toList());
                                                      if (!missingThumbnails.isEmpty())
                                                        return createVideoThumbnails(
                                                            params.getT1().getNameString(),
                                                            params.getT2(),
                                                            metadata,
                                                            missingThumbnails);
                                                    }
                                                    return Mono.just(true);
                                                  })
                                              .flatMap(
                                                  params -> {
                                                    final Metadata metadata = params.getT3();
                                                    final ObjectId imageFileId =
                                                        params.getT1().getFileId();
                                                    return albumDataEntryRepository
                                                        .save(
                                                            createAlbumEntry(
                                                                albumId,
                                                                imageFileId,
                                                                params.getT1().getNameString(),
                                                                metadata,
                                                                params.getT4(),
                                                                params.getT5()))
                                                        .doOnNext(
                                                            f -> log.info("stored " + filename));
                                                  });
                                        },
                                        1)
                                    .count()
                                    .map(
                                        processedCount ->
                                            jobFiles.size() == processedCount.intValue())))
            .onErrorResume(
                ex -> {
                  log.error("Cannot process " + jobProperties, ex);
                  return Mono.just(Boolean.FALSE);
                })
            .block());
  }

  private Mono<Tuple2<GitAccess.GitFileEntry, File>> readFileEntryToTemp(
      final GitAccess ga, final GitAccess.GitFileEntry fileEntry) {
    return ga.readObject(fileEntry.getFileId())
        .flatMap(
            loader ->
                asyncService.asyncMono(
                    () -> {
                      final File tempFile =
                          File.createTempFile("tmp", fileEntry.getNameString().replace('/', '_'));
                      try (final FileOutputStream os = new FileOutputStream(tempFile)) {
                        loader.copyTo(os);
                      }
                      return Tuples.of(fileEntry, tempFile);
                    }));
  }

  private boolean createImageThumbnails(
      final UUID albumId,
      final ObjectId entryId,
      final BufferedImage image,
      final Optional<TiffOutputSet> optionalTiffOutputSet,
      final Collection<ThumbnailFilenameService.FileAndScale> remainingFiles,
      final int orientation)
      throws ImageWriteException, IOException, ImageReadException {
    final int width = image.getWidth();
    final int height = image.getHeight();
    int maxLength = Math.max(width, height);
    final Stream<ThumbnailFilenameService.FileAndScale> s =
        thumbnailFilenameService.listThumbnailsOf(albumId, entryId);
    for (ThumbnailFilenameService.FileAndScale fileAndScale : remainingFiles) {
      int size = fileAndScale.getSize();
      File targetFile = fileAndScale.getFile();

      if (targetFile.exists()) continue;
      {
        // log.info("Scale " + filename + " to " + size);
        final File parentDir = targetFile.getParentFile();
        if (!parentDir.exists()) {
          if (!parentDir.mkdirs()) return false;
        }
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

            final TiffOutputDirectory tiffOutputDirectory = tiffOutputSet.getRootDirectory();
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
                    new BufferedOutputStream(new FileOutputStream(tempFile), 256 * 1024);
                new ExifRewriter()
                    .updateExifMetadataLossless(baos.toByteArray(), os, tiffOutputSet);
              }
              if (!tempFile.renameTo(targetFile)) return false;
            }
          } else {
            final boolean writeOk = ImageIO.write(targetImage, "jpg", tempFile);
            if (writeOk) {
              if (!tempFile.renameTo(targetFile)) return false;
            }
          }
        } finally {
          // log.info("delete " + tempFile);
          tempFile.delete();
          // log.info("written: " + targetFile);
        }
      }
    }
    return true;
  }

  private Mono<Boolean> createVideoThumbnails(
      final String filename,
      final File file,
      final Metadata metadata,
      final List<ThumbnailFilenameService.FileAndScale> missingOutputs) {
    double duration = Double.parseDouble(metadata.get(XMPDM.DURATION));
    final Integer width = metadata.getInt(TIFF.IMAGE_WIDTH);
    final Integer height = metadata.getInt(TIFF.IMAGE_LENGTH);
    final NumberFormat numberInstance = NumberFormat.getNumberInstance();
    numberInstance.setMaximumFractionDigits(3);
    numberInstance.setMinimumFractionDigits(3);
    final String thumbnailPos = numberInstance.format(duration / 3);

    return Flux.fromIterable(missingOutputs)
        .flatMap(
            fileAndScale -> {
              int targetLength = Math.min(fileAndScale.getSize(), Math.max(width, height));
              int adjustedLength = targetLength - targetLength % 2;
              final String scale;
              if (width > height) {
                int targetHeight = targetLength * height / width;
                scale = adjustedLength + ":" + (targetHeight - targetHeight % 2);
              } else {
                int targetWidth = targetLength * width / height;
                scale = (targetWidth - targetWidth % 2) + ":" + adjustedLength;
              }

              final File imgTargetFile = fileAndScale.getFile();

              final File videoTargetFile = fileAndScale.getVideoFile();
              final Mono<Tuple2<ExecuteResult, Boolean>> imgResult;
              if (imgTargetFile.exists()) imgResult = Mono.empty();
              else {
                if (!imgTargetFile.getParentFile().exists()) imgTargetFile.getParentFile().mkdirs();
                final File tempFile =
                    new File(imgTargetFile.getParentFile(), imgTargetFile.getName() + "-tmp.jpg");
                imgResult =
                    Mono.defer(
                        () ->
                            execute(
                                    new String[] {
                                      "ffmpeg",
                                      "-y",
                                      "-i",
                                      file.getAbsolutePath(),
                                      "-ss",
                                      thumbnailPos,
                                      "-vframes",
                                      "1",
                                      "-vf",
                                      "scale=" + scale,
                                      tempFile.getAbsolutePath()
                                    })
                                // .log("thumb " + scale)
                                .map(
                                    r -> {
                                      if (r.getCode() == 0) {
                                        return Tuples.of(r, tempFile.renameTo(imgTargetFile));
                                      } else {
                                        tempFile.delete();
                                        return Tuples.of(r, false);
                                      }
                                    })
                                .timeout(Duration.ofHours(1))
                        //            .log("img " + scale)
                        );
              }
              final Mono<Tuple2<ExecuteResult, Boolean>> videoResult;
              if (videoTargetFile.exists()) videoResult = Mono.empty();
              else {
                final File tempFile =
                    new File(
                        videoTargetFile.getParentFile(), videoTargetFile.getName() + "-tmp.mp4");
                videoResult =
                    Mono.defer(
                        () ->
                            execute(
                                    new String[] {
                                      "ffmpeg",
                                      "-y",
                                      "-hwaccel",
                                      "auto",
                                      "-i",
                                      file.getAbsolutePath(),
                                      "-preset",
                                      "faster",
                                      "-movflags",
                                      "+faststart",
                                      "-vf",
                                      "scale=" + scale,
                                      tempFile.getAbsolutePath()
                                    })
                                // .log("vid " + scale)
                                .map(
                                    r -> {
                                      if (r.getCode() == 0) {
                                        return Tuples.of(r, tempFile.renameTo(videoTargetFile));
                                      } else {
                                        tempFile.delete();
                                        return Tuples.of(r, false);
                                      }
                                    })
                                .timeout(Duration.ofHours(5))
                        //            .log("vid " + scale)
                        );
              }

              return Flux.concat(imgResult, videoResult)
                  .map(Tuple2::getT2)
                  .all(ok -> ok)
                  .defaultIfEmpty(true)
              // .log("all :" + targetLength)
              ;
            },
            1)
        .all(ok -> ok)
    // .log("all")
    ;
  }

  private Mono<ExecuteResult> execute(final String[] cmdarray) {
    try {
      final long startTime = System.nanoTime();
      final Process process = Runtime.getRuntime().exec(cmdarray);
      final StringBuffer stdOutBuffer = new StringBuffer();
      final StringBuffer stdErrBuffer = new StringBuffer();
      new Thread(
              () -> {
                try {
                  final BufferedReader reader =
                      new BufferedReader(new InputStreamReader(process.getInputStream()));

                  while (true) {
                    final String line = reader.readLine();
                    if (line == null) break;
                    stdOutBuffer.append(line);
                    stdOutBuffer.append('\n');
                    log.info("STDOUT: " + line);
                  }
                } catch (IOException e) {
                  log.warn("Cannot read stdout", e);
                }
              })
          .start();
      new Thread(
              () -> {
                try {
                  final BufferedReader reader =
                      new BufferedReader(new InputStreamReader(process.getErrorStream()));

                  while (true) {
                    final String line = reader.readLine();
                    if (line == null) break;
                    stdErrBuffer.append(line);
                    stdErrBuffer.append('\n');
                    log.info("STDERR: " + line);
                  }
                } catch (IOException e) {
                  log.warn("Cannot read stdout", e);
                }
              })
          .start();

      return Mono.fromFuture(process.onExit())
          .map(
              p -> {
                final Duration duration = Duration.ofNanos(System.nanoTime() - startTime);
                log.info("Processed in " + duration + ": " + String.join(" ", cmdarray));
                return new ExecuteResult(p.exitValue());
              });

    } catch (IOException e) {
      return Mono.error(e);
    }
  }

  @Value
  private static class ExecuteResult {
    // String stdErr;
    // String stdOut;
    int code;
  }
}
