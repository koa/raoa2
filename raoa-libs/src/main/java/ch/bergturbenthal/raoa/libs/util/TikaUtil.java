package ch.bergturbenthal.raoa.libs.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Property;
import org.apache.tika.metadata.TIFF;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.metadata.XMPDM;
import org.jetbrains.annotations.NotNull;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class TikaUtil {

  private static final Pattern MEDIA_DURATION_HMS_PATTERN =
      Pattern.compile("([0-9]{1,2}):([0-9]{1,2}):([0-9]{1,2})");
  private static final Pattern MEDIA_DURATION_SECONDS_PATTERN =
      Pattern.compile("((?:[0-9]+)(?:\\.(?:[0-9]+))?) ?s");
  private static final Pattern FOCAL_LENGTH_PATTERN =
      Pattern.compile("((:?[0-9]+)(:?\\.(:?[0-9]+))?) mm");
  private static final Property PROPERTY_VIDEO_CAMERA_MODEL = Property.internalText("Model");
  private static final Property PROPERTY_EXIF_LENS =
      Property.internalText("Exif SubIFD:Lens Model");
  private static final Property PROPERTY_VIDEO_LENS = Property.internalText("Lens Model");
  private static final DateFormat MEDIA_CREATE_DATE_FORMAT =
      new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
  private static final DateFormat MEDIA_CREATE_DATE_FORMAT2 =
      new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
  private static final Pattern NUMBER_PATTERN = Pattern.compile("[0-9.]+");
  private static Map<String, DateFormat> MEDIA_CREATE_DATE_FORMATS =
      Collections.synchronizedMap(new HashMap<>());

  static {
    MEDIA_CREATE_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    MEDIA_CREATE_DATE_FORMAT2.setTimeZone(TimeZone.getTimeZone("UTC"));
  }

  private static DateFormat createFomat2AtZone(String zone) {
    return MEDIA_CREATE_DATE_FORMATS.computeIfAbsent(
        zone,
        k -> {
          final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
          final ZoneId zoneId = ZoneId.of(zone);
          TimeZone timeZone = TimeZone.getTimeZone(zoneId);
          dateFormat.setTimeZone(timeZone);
          return dateFormat;
        });
  }

  public static Optional<Integer> extractTargetWidth(Metadata metadata) {
    if (Optional.ofNullable(metadata.get(TIFF.ORIENTATION)).map(Integer::valueOf).orElse(0) <= 4) {
      return extractWidth(metadata);
    } else {
      return extractHeight(metadata);
    }
  }

  public static Optional<Integer> extractHeight(final Metadata metadata) {
    return TikaUtil.extractInteger(
        metadata, TIFF.IMAGE_LENGTH, Property.internalInteger("Image Height"));
  }

  public static Optional<Integer> extractWidth(final Metadata metadata) {
    return TikaUtil.extractInteger(
        metadata, TIFF.IMAGE_WIDTH, Property.internalInteger("Image Width"));
  }

  public static Optional<Integer> extractTargetHeight(Metadata metadata) {
    if (Optional.ofNullable(metadata.get(TIFF.ORIENTATION)).map(Integer::valueOf).orElse(0) <= 4) {
      return extractHeight(metadata);
    } else {
      return extractWidth(metadata);
    }
  }

  public static Optional<Instant> extractCreateTime(Metadata metadata) {
    final String timezone = metadata.get("Exif SubIFD:Time Zone");
    final Optional<Instant> createdDate =
        TikaUtil.extractInstant(metadata, timezone, TikaCoreProperties.CREATED);
    if (createdDate.isPresent()) {
      return createdDate;
    }
    final String mediaCreateDate = metadata.get("Media Create Date");
    if (mediaCreateDate != null) {
      try {

        final Date date = MEDIA_CREATE_DATE_FORMAT.parse(mediaCreateDate);
        return Optional.ofNullable(date.toInstant());
      } catch (ParseException e) {
        log.warn("Could not parse create date {}", mediaCreateDate, e);
      }
    }
    return Optional.empty();
  }

  public static Optional<Duration> extractVideoDuration(Metadata metadata) {
    final String durationString = metadata.get(XMPDM.DURATION);
    if (durationString != null) {
      try {
        double duration = Double.parseDouble(durationString) * 1000;
        return Optional.of(Duration.ofMillis(Math.round(duration)));
      } catch (NumberFormatException e) {
        log.warn("Cannot parse duration", e);
      }
    }
    final String mediaDuration = metadata.get("Media Duration");
    if (mediaDuration != null) {
      final Matcher hmsMatcher = MEDIA_DURATION_HMS_PATTERN.matcher(mediaDuration);
      if (hmsMatcher.matches()) {
        try {
          final long seconds =
              Long.parseLong(hmsMatcher.group(1)) * 3600
                  + Long.parseLong(hmsMatcher.group(2)) * 60
                  + Long.parseLong(hmsMatcher.group(3));
          return Optional.of(Duration.ofSeconds(seconds));
        } catch (NumberFormatException e) {
          log.warn("Cannot parse duration {}", mediaDuration, e);
        }
      } else {
        final Matcher secondMatcher = MEDIA_DURATION_SECONDS_PATTERN.matcher(mediaDuration);
        if (secondMatcher.matches()) {
          try {
            final double seconds = Double.parseDouble(secondMatcher.group(1));
            return Optional.of(Duration.ofMillis((long) (seconds * 1000.0)));
          } catch (NumberFormatException e) {
            log.warn("Cannot parse duration {}", mediaDuration, e);
          }
        }
      }
      log.warn("Duration don't match pattern {}", mediaDuration);
    }
    return Optional.empty();
  }

  private static Optional<Integer> extractInteger(final Metadata m, final Property... property) {
    for (final Property p : property) {
      final Integer value = m.getInt(p);
      if (value != null) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }

  private static Optional<String> extractString(final Metadata m, final Property... property) {
    for (final Property p : property) {
      final String value = m.get(p);
      if (value != null) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }

  private static Optional<Instant> extractInstant(
      final Metadata m, final String timezone, final Property... property) {
    for (final Property p : property) {
      final String s = m.get(p);
      if (s != null) {
        try {
          final DateFormat format = createFomat2AtZone(timezone);
          synchronized (format) {
            final Date value = format.parse(s);
            return Optional.of(value.toInstant());
          }
        } catch (ParseException e) {
          log.warn("Could not parse date {}", s, e);
        }
      }
      final Date value = m.getDate(p);
      if (value != null) {
        return Optional.of(value.toInstant());
      }
    }
    return Optional.empty();
  }

  private static Optional<Double> extractDouble(final Metadata m, final Property... property) {
    for (final Property p : property) {
      final String value = m.get(p);
      if (value != null) {
        return Optional.of(Double.valueOf(value));
      }
    }
    return Optional.empty();
  }

  public static Optional<String> extractCameraModel(final Metadata metadata) {
    return TikaUtil.extractString(metadata, TIFF.EQUIPMENT_MODEL, PROPERTY_VIDEO_CAMERA_MODEL);
  }

  public static Optional<String> extractLensModel(final Metadata metadata) {
    return TikaUtil.extractString(metadata, PROPERTY_EXIF_LENS, PROPERTY_VIDEO_LENS);
  }

  public static Optional<String> extractMake(final Metadata metadata) {
    return TikaUtil.extractString(metadata, TIFF.EQUIPMENT_MAKE, Property.internalText("Make"));
  }

  public static Optional<Double> extractFocalLength(final Metadata metadata) {
    final Optional<Double> exifLength = TikaUtil.extractDouble(metadata, TIFF.FOCAL_LENGTH);
    if (exifLength.isPresent()) {
      return exifLength;
    }

    return TikaUtil.extractString(metadata, Property.internalReal("Focal Length"))
        .flatMap(TikaUtil::parseFocalLength);
  }

  private static @NotNull Optional<Double> parseFocalLength(final String input) {
    final Matcher matcher = FOCAL_LENGTH_PATTERN.matcher(input);
    if (matcher.matches()) {
      try {
        final double length = Double.parseDouble(matcher.group(1));
        return Optional.of(length);
      } catch (NumberFormatException e) {
        log.warn("Cannot parse focal length {}", input, e);
      }
    }
    return Optional.empty();
  }

  public static Optional<Double> extractFNumber(final Metadata metadata) {
    return TikaUtil.extractDouble(metadata, TIFF.F_NUMBER, Property.internalReal("F Number"));
  }

  public static Optional<Double> extractExposureTime(final Metadata metadata) {
    return TikaUtil.extractDouble(metadata, TIFF.EXPOSURE_TIME);
  }

  public static Optional<Integer> extractIsoSpeed(final Metadata metadata) {
    return TikaUtil.extractInteger(
        metadata,
        TIFF.ISO_SPEED_RATINGS,
        Property.internalInteger("exif:IsoSpeedRatings"),
        Property.internalInteger("ISO"));
  }

  public static Optional<Double> extractFocalLength35(final Metadata metadata) {
    return TikaUtil.extractString(metadata, Property.internalReal("Exif SubIFD:Focal Length 35"))
        .flatMap(TikaUtil::parseFocalLength);
  }

  public static Optional<String> extractContentType(final Metadata metadata) {
    return TikaUtil.extractString(metadata, Property.externalText(HttpHeaders.CONTENT_TYPE));
  }

  public static Optional<Double> extractLatitude(final Metadata metadata) {
    return TikaUtil.extractDouble(metadata, TikaCoreProperties.LATITUDE);
  }

  public static Optional<Double> extractLongitude(final Metadata metadata) {
    return TikaUtil.extractDouble(metadata, TikaCoreProperties.LONGITUDE);
  }

  public static int extractOrientation(final Metadata metadata) {
    return Optional.ofNullable(metadata.get(TIFF.ORIENTATION)).map(Integer::parseInt).orElse(1);
  }
}
