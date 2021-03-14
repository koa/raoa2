package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.elastic.model.AlbumData;
import ch.bergturbenthal.raoa.elastic.model.AlbumEntryData;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.viewer.model.graphql.*;
import com.coxautodev.graphql.tools.GraphQLResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.eclipse.jgit.lib.ObjectId;
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

  // TODO: resolve also groups
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

  public CompletableFuture<List<UserReference>> canAccessedByUser(Album album) {
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
        .map(e -> createAlbumEntry(album, e))
        .collectList()
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<AlbumEntry> getAlbumEntry(Album album, String entryId) {
    return dataViewService
        .loadEntry(album.getId(), ObjectId.fromString(entryId))
        .map(e -> createAlbumEntry(album, e))
        .timeout(TIMEOUT)
        .toFuture();
  }

  @NotNull
  private AlbumEntry createAlbumEntry(final Album album, final AlbumEntryData entry) {
    return new AlbumEntry(album, entry.getEntryId().name(), entry.getFilename(), entry);
  }

  public CompletableFuture<String> getName(Album album) {
    return extractElField(album, AlbumData::getName);
  }

  @NotNull
  private <T> CompletableFuture<T> extractElField(
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

  public CompletableFuture<String> getVersion(Album album) {
    return extractElField(album, albumData -> albumData.getCurrentVersion().name());
  }

  public CompletableFuture<Instant> getAlbumTime(Album album) {
    return extractElField(album, AlbumData::getCreateTime);
  }

  public CompletableFuture<List<GroupReference>> canAccessedByGroup(Album album) {
    final QueryContext context = album.getContext();
    return dataViewService
        .listGroups()
        .filter(g -> context.canAccessGroup(g.getId()))
        .filter(g -> g.getVisibleAlbums().contains(album.getId()))
        .map(group -> new GroupReference(group.getId(), context, Mono.just(group)))
        .collectList()
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<List<LabelValue>> labels(Album album) {
    return album
        .getElAlbumData()
        .map(d -> Optional.ofNullable(d.getLabels()))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .flatMapIterable(Map::entrySet)
        .map(e -> new LabelValue(e.getKey(), e.getValue()))
        .collectList()
        .defaultIfEmpty(Collections.emptyList())
        .timeout(TIMEOUT)
        .toFuture();
  }

  public CompletableFuture<List<KeywordCount>> keywordCounts(Album album) {
    return album
        .getElAlbumData()
        .flatMapIterable(AlbumData::getKeywordCount)
        .map(k -> new KeywordCount(k.getKeyword(), k.getEntryCount()))
        .collectList()
        .timeout(TIMEOUT)
        .toFuture();
  }
}
