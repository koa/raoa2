package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.viewer.model.elasticsearch.AlbumData;
import ch.bergturbenthal.raoa.viewer.model.graphql.Album;
import ch.bergturbenthal.raoa.viewer.model.graphql.AlbumEntry;
import ch.bergturbenthal.raoa.viewer.model.graphql.UserReference;
import ch.bergturbenthal.raoa.viewer.service.DataViewService;
import com.coxautodev.graphql.tools.GraphQLResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class AlbumQuery implements GraphQLResolver<Album> {
  private static final Duration TIMEOUT = Duration.ofMinutes(5);

  private final DataViewService dataViewService;

  public AlbumQuery(final DataViewService dataViewService) {
    this.dataViewService = dataViewService;
  }

  public String getZipDownloadUri(Album album) {
    return album.getContext().getContexRootPath() + "/rest/album-zip/" + album.getId().toString();
  }

  public CompletableFuture<List<UserReference>> canAccessedBy(Album album) {
    if (album.getContext().canUserManageUsers()) {
      return dataViewService
          .listUserForAlbum(album.getId())
          .map(u -> new UserReference(u.getId(), u.getUserData(), album.getContext()))
          .collectList()
          .timeout(TIMEOUT)
          .toFuture();
    }
    return CompletableFuture.completedFuture(Collections.emptyList());
  }

  public CompletableFuture<List<AlbumEntry>> getEntries(Album album) {
    return dataViewService
        .listEntries(album.getId())
        .map(
            e ->
                new AlbumEntry(
                    album,
                    e.getEntryId().name(),
                    e.getFilename(),
                    dataViewService.loadEntry(album.getId(), e.getEntryId()).cache()))
        .collectList()
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<String> getName(Album album) {
    return extractElField(album, AlbumData::getName);
  }

  @NotNull
  public <T> CompletableFuture<T> extractElField(
      final Album album, final Function<AlbumData, T> extractor) {
    return album
        .getElAlbumData()
        .flatMap(d -> Mono.justOrEmpty(extractor.apply(d)))
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<Integer> getEntryCount(Album album) {
    return extractElField(album, AlbumData::getEntryCount);
  }

  public CompletableFuture<Instant> getAlbumTime(Album album) {
    return extractElField(album, AlbumData::getCreateTime);
  }
}
