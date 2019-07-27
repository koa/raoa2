package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.viewer.model.graphql.AlbumEntry;
import com.coxautodev.graphql.tools.GraphQLResolver;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.function.Function;
import javax.servlet.http.HttpServletRequest;
import org.apache.tika.metadata.*;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class AlbumEntryQuery implements GraphQLResolver<AlbumEntry> {
  private final AlbumList albumList;

  public AlbumEntryQuery(final AlbumList albumList) {
    this.albumList = albumList;
  }

  public Optional<String> getContentType(AlbumEntry entry) {
    return extractMetadataProperty(entry, Property.externalText(HttpHeaders.CONTENT_TYPE));
  }

  public String getThumbnailUri(AlbumEntry entry) {
    RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
    final String contextPath;
    if (requestAttributes instanceof ServletRequestAttributes) {
      HttpServletRequest request = ((ServletRequestAttributes) requestAttributes).getRequest();
      final String contextPath1 = request.getServletPath();

      final String contextPath2 = request.getRequestURL().toString();
      contextPath = contextPath2.substring(0, contextPath2.length() - contextPath1.length());
    } else contextPath = "";
    return contextPath + "/album/" + entry.getAlbum().getId().toString() + "/" + entry.getId();
  }

  public Optional<Instant> getCreated(AlbumEntry entry) {
    return extractMetadataInstant(entry, TikaCoreProperties.CREATED);
  }

  public Optional<Integer> getWidth(AlbumEntry entry) {
    return extractInteger(entry, TIFF.IMAGE_WIDTH);
  }

  public Optional<Integer> getHeight(AlbumEntry entry) {
    return extractInteger(entry, TIFF.IMAGE_LENGTH);
  }

  public Optional<Integer> getTargetWidth(AlbumEntry entry) {
    return extractMetadataValue(
        entry,
        m ->
            Optional.ofNullable(m.get(TIFF.ORIENTATION))
                .map(Integer::valueOf)
                .flatMap(
                    o -> {
                      if (o <= 4) {
                        return Optional.ofNullable(m.getInt(TIFF.IMAGE_WIDTH));
                      } else {
                        return Optional.ofNullable(m.getInt(TIFF.IMAGE_LENGTH));
                      }
                    })
                .orElse(null));
  }

  public Optional<Integer> getTargetHeight(AlbumEntry entry) {
    return extractMetadataValue(
        entry,
        m ->
            Optional.ofNullable(m.get(TIFF.ORIENTATION))
                .map(Integer::valueOf)
                .flatMap(
                    o -> {
                      if (o <= 4) {
                        return Optional.ofNullable(m.getInt(TIFF.IMAGE_LENGTH));
                      } else {
                        return Optional.ofNullable(m.getInt(TIFF.IMAGE_WIDTH));
                      }
                    })
                .orElse(null));
  }

  private Optional<Integer> extractInteger(final AlbumEntry entry, final Property property) {
    return extractMetadataValue(entry, m -> m.getInt(property));
  }

  private Optional<Instant> extractMetadataInstant(
      final AlbumEntry entry, final Property property) {
    return extractMetadataValue(entry, m -> m.getDate(property)).map(Date::toInstant);
  }

  private Optional<String> extractMetadataProperty(final AlbumEntry entry, final Property format) {
    return extractMetadataValue(entry, m -> m.get(format));
  }

  private <V> Optional<V> extractMetadataValue(
      final AlbumEntry entry, final Function<Metadata, V> valueExtractor) {
    return albumList
        .getAlbum(entry.getAlbum().getId())
        .flatMap(
            a -> {
              try {
                return a.entryMetdata(ObjectId.fromString(entry.getId()));
              } catch (IOException e) {
                throw new RuntimeException("Cannot take metadata", e);
              }
            })
        .map(valueExtractor);
  }
}
