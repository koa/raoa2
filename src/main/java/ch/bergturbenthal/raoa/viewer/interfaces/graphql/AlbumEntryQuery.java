package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.viewer.model.graphql.AlbumEntry;
import com.coxautodev.graphql.tools.GraphQLResolver;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.function.Function;
import org.apache.tika.metadata.*;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.stereotype.Component;

@Component
public class AlbumEntryQuery implements GraphQLResolver<AlbumEntry> {
  private final AlbumList albumList;

  public AlbumEntryQuery(final AlbumList albumList) {
    this.albumList = albumList;
  }

  public Optional<String> getContentType(AlbumEntry entry) {
    return extractMetadataProperty(entry, Property.externalText(HttpHeaders.CONTENT_TYPE));
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
