package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.viewer.model.graphql.AlbumEntry;
import com.coxautodev.graphql.tools.GraphQLResolver;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javax.servlet.http.HttpServletRequest;
import org.apache.tika.metadata.*;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import reactor.core.publisher.Mono;

@Component
public class AlbumEntryQuery implements GraphQLResolver<AlbumEntry> {
  private final AlbumList albumList;

  public AlbumEntryQuery(final AlbumList albumList) {
    this.albumList = albumList;
  }

  public CompletableFuture<String> getContentType(AlbumEntry entry) {
    return extractMetadataProperty(entry, Property.externalText(HttpHeaders.CONTENT_TYPE))
        .toFuture();
  }

  public String getEntryUri(AlbumEntry entry) {
    RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
    final String contextPath;
    if (requestAttributes instanceof ServletRequestAttributes) {
      HttpServletRequest request = ((ServletRequestAttributes) requestAttributes).getRequest();
      final String contextPath1 = request.getServletPath();

      final String contextPath2 = request.getRequestURL().toString();
      contextPath = contextPath2.substring(0, contextPath2.length() - contextPath1.length());
    } else contextPath = "";
    return contextPath + "/rest/album/" + entry.getAlbum().getId().toString() + "/" + entry.getId();
  }

  public String getThumbnailUri(AlbumEntry entry) {
    return getEntryUri(entry) + "/thumbnail";
  }

  public String getOriginalUri(AlbumEntry entry) {
    return getEntryUri(entry) + "/original";
  }

  public CompletableFuture<Instant> getCreated(AlbumEntry entry) {
    return extractMetadataInstant(entry, TikaCoreProperties.CREATED).toFuture();
  }

  public CompletableFuture<Integer> getWidth(AlbumEntry entry) {
    return extractInteger(entry, TIFF.IMAGE_WIDTH).toFuture();
  }

  public CompletableFuture<Integer> getHeight(AlbumEntry entry) {
    return extractInteger(entry, TIFF.IMAGE_LENGTH).toFuture();
  }

  public CompletableFuture<String> getCameraModel(AlbumEntry entry) {
    return extractMetadataProperty(entry, TIFF.EQUIPMENT_MODEL).toFuture();
  }

  public CompletableFuture<String> getCameraManufacturer(AlbumEntry entry) {
    return extractMetadataProperty(entry, TIFF.EQUIPMENT_MAKE).toFuture();
  }

  public CompletableFuture<Integer> getFocalLength(AlbumEntry entry) {
    return extractInteger(entry, TIFF.FOCAL_LENGTH).toFuture();
  }

  public CompletableFuture<Double> getFNumber(AlbumEntry entry) {
    return extractDouble(entry, TIFF.F_NUMBER).toFuture();
  }

  private Mono<Double> extractDouble(final AlbumEntry entry, final Property property) {
    return extractMetadataValue(entry, m -> m.get(property)).map(Double::valueOf);
  }

  public CompletableFuture<Integer> getTargetWidth(AlbumEntry entry) {
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
                    .orElse(null))
        .toFuture();
  }

  public CompletableFuture<Integer> getTargetHeight(AlbumEntry entry) {
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
                    .orElse(null))
        .toFuture();
  }

  private Mono<Integer> extractInteger(final AlbumEntry entry, final Property property) {
    return extractMetadataValue(entry, m -> m.getInt(property));
  }

  private Mono<Instant> extractMetadataInstant(final AlbumEntry entry, final Property property) {
    return extractMetadataValue(entry, m -> m.getDate(property)).map(Date::toInstant);
  }

  private Mono<String> extractMetadataProperty(final AlbumEntry entry, final Property format) {
    return extractMetadataValue(entry, m -> m.get(format));
  }

  Mono<GitAccess> takeAlbum(UUID albumId) {
    return albumList.getAlbum(albumId);
  }

  private <V> Mono<V> extractMetadataValue(
      final AlbumEntry entry, final Function<Metadata, V> valueExtractor) {
    final Mono<GitAccess> album = albumList.getAlbum(entry.getAlbum().getId());
    return album
        .flatMap(a -> a.entryMetdata(ObjectId.fromString(entry.getId())))
        .map(v -> Optional.ofNullable(valueExtractor.apply(v)))
        .filter(Optional::isPresent)
        .map(Optional::get);
  }
}
