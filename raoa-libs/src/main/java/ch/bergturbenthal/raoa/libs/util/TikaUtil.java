package ch.bergturbenthal.raoa.libs.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Property;
import org.apache.tika.metadata.TIFF;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.metadata.XMPDM;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class TikaUtil {

    public static final Pattern MEDIA_DURATION_PATTERN = Pattern.compile("([0-9]{1,2}):([0-9]{1,2}):([0-9]{1,2})");

    public static Optional<Integer> extractTargetWidth(Metadata metadata) {
        return TikaUtil.extractInteger(metadata, TIFF.IMAGE_WIDTH, Property.internalInteger("Image Width"));
    }

    public static Optional<Integer> extractTargetHeight(Metadata metadata) {
        return TikaUtil.extractInteger(metadata, TIFF.IMAGE_LENGTH, Property.internalInteger("Image Height"));
    }

    public static Optional<Instant> extractCreateTime(Metadata metadata) {
        return TikaUtil.extractInstant(metadata, TikaCoreProperties.CREATED);
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
            final Matcher matcher = MEDIA_DURATION_PATTERN.matcher(mediaDuration);
            if (matcher.matches()) {
                try {
                    final long seconds = Long.parseLong(matcher.group(1)) * 3600 + Long.parseLong(matcher.group(2)) * 60
                            + Long.parseLong(matcher.group(3));
                    return Optional.of(Duration.ofSeconds(seconds));
                } catch (NumberFormatException e) {
                    log.warn("Cannot parse duration " + mediaDuration, e);
                }
            }
            log.warn("Duration don't match pattern " + mediaDuration);
        }
        return Optional.empty();
    }

    public static Optional<Integer> extractInteger(final Metadata m, final Property... property) {
        for (final Property p : property) {
            final Integer value = m.getInt(p);
            if (value != null) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    public static Optional<String> extractString(final Metadata m, final Property... property) {
        for (final Property p : property) {
            final String value = m.get(p);
            if (value != null) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    public static Optional<Instant> extractInstant(final Metadata m, final Property... property) {
        for (final Property p : property) {
            final Date value = m.getDate(p);
            if (value != null) {
                return Optional.of(value.toInstant());
            }
        }
        return Optional.empty();
    }

    public static Optional<Double> extractDouble(final Metadata m, final Property... property) {
        for (final Property p : property) {
            final String value = m.get(p);
            if (value != null) {
                return Optional.of(Double.valueOf(value));
            }
        }
        return Optional.empty();
    }
}
