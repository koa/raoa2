package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.viewer.model.graphql.AlbumEntry;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import reactor.core.publisher.Mono;

@Component
public class AlbumEntryQuery implements GraphQLResolver<AlbumEntry> {
  private final AlbumList albumList;
  private final AuthorizationManager authorizationManager;

  public AlbumEntryQuery(
      final AlbumList albumList, final AuthorizationManager authorizationManager) {
    this.albumList = albumList;
    this.authorizationManager = authorizationManager;
  }

  public CompletableFuture<String> getContentType(AlbumEntry entry) {
    final SecurityContext context = SecurityContextHolder.getContext();
    return extractMetadataProperty(context, entry, Property.externalText(HttpHeaders.CONTENT_TYPE))
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
    final SecurityContext context = SecurityContextHolder.getContext();
    return extractMetadataInstant(context, entry, TikaCoreProperties.CREATED).toFuture();
  }

  public CompletableFuture<Integer> getWidth(AlbumEntry entry) {
    final SecurityContext context = SecurityContextHolder.getContext();
    return extractInteger(entry, TIFF.IMAGE_WIDTH).toFuture();
  }

  public CompletableFuture<Integer> getHeight(AlbumEntry entry) {
    final SecurityContext context = SecurityContextHolder.getContext();
    return extractInteger(entry, TIFF.IMAGE_LENGTH).toFuture();
  }

  public CompletableFuture<String> getCameraModel(AlbumEntry entry) {
    final SecurityContext context = SecurityContextHolder.getContext();
    return extractMetadataProperty(context, entry, TIFF.EQUIPMENT_MODEL).toFuture();
  }

  public CompletableFuture<String> getCameraManufacturer(AlbumEntry entry) {
    final SecurityContext context = SecurityContextHolder.getContext();
    return extractMetadataProperty(context, entry, TIFF.EQUIPMENT_MAKE).toFuture();
  }

  public CompletableFuture<Integer> getFocalLength(AlbumEntry entry) {
    final SecurityContext context = SecurityContextHolder.getContext();
    return extractInteger(entry, TIFF.FOCAL_LENGTH).toFuture();
  }

  public CompletableFuture<Double> getFNumber(AlbumEntry entry) {
    final SecurityContext context = SecurityContextHolder.getContext();
    return extractDouble(entry, TIFF.F_NUMBER).toFuture();
  }

  private Mono<Double> extractDouble(final AlbumEntry entry, final Property property) {
    final SecurityContext context = SecurityContextHolder.getContext();
    return extractMetadataValue(context, entry, m -> m.get(property)).map(Double::valueOf);
  }

  public CompletableFuture<Integer> getTargetWidth(AlbumEntry entry) {
    final SecurityContext context = SecurityContextHolder.getContext();
    return extractMetadataValue(
            context,
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
    final SecurityContext context = SecurityContextHolder.getContext();
    return extractMetadataValue(
            context,
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
    final SecurityContext context = SecurityContextHolder.getContext();
    return extractMetadataValue(context, entry, m -> m.getInt(property));
  }

  private Mono<Instant> extractMetadataInstant(
      final SecurityContext context, final AlbumEntry entry, final Property property) {
    return extractMetadataValue(context, entry, m -> m.getDate(property)).map(Date::toInstant);
  }

  private Mono<String> extractMetadataProperty(
      final SecurityContext context, final AlbumEntry entry, final Property format) {
    return extractMetadataValue(context, entry, m -> m.get(format));
  }

  private Mono<GitAccess> takeAlbum(UUID albumId) {
    return albumList.getAlbum(albumId);
  }

  private <V> Mono<V> extractMetadataValue(
      SecurityContext context, final AlbumEntry entry, final Function<Metadata, V> valueExtractor) {
    return authorizationManager
        .canUserAccessToAlbum(context, entry.getAlbum().getId())
        .filter(t -> t)
        .flatMap(t -> albumList.getAlbum(entry.getAlbum().getId()))
        .flatMap(a -> a.entryMetdata(ObjectId.fromString(entry.getId())))
        .map(v -> Optional.ofNullable(valueExtractor.apply(v)))
        .filter(Optional::isPresent)
        .map(Optional::get);
  }
}
