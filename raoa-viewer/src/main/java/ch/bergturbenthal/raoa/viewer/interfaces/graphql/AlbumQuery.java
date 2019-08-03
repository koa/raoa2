package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.viewer.model.graphql.Album;
import ch.bergturbenthal.raoa.viewer.model.graphql.AlbumEntry;
import com.coxautodev.graphql.tools.GraphQLResolver;
import java.util.concurrent.CompletableFuture;
import org.eclipse.jgit.treewalk.filter.OrTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class AlbumQuery implements GraphQLResolver<Album> {
  private static final TreeFilter ENTRIES_FILTER =
      OrTreeFilter.create(
          new TreeFilter[] {
            PathSuffixFilter.create(".JPG"), PathSuffixFilter.create(".JPEG"),
            PathSuffixFilter.create(".jpg"), PathSuffixFilter.create(".jpeg")
          });
  private final AlbumList albumList;

  public AlbumQuery(final AlbumList albumList) {
    this.albumList = albumList;
  }

  public Iterable<AlbumEntry> getEntries(Album album) {
    return streamEntries(album)
        .map(e -> new AlbumEntry(album, e.getFileId().name(), e.getNameString()))
        // .log("entries")
        .toIterable();
  }

  @NotNull
  private Flux<GitAccess.GitFileEntry> streamEntries(final Album album) {
    return albumList.getAlbum(album.getId()).flatMapMany(e -> e.listFiles(ENTRIES_FILTER));
  }

  public CompletableFuture<String> getName(Album album) {
    return albumList.getAlbum(album.getId()).flatMap(GitAccess::getName).toFuture();
  }

  public CompletableFuture<Long> getEntryCount(Album album) {
    return streamEntries(album).count().toFuture();
  }
}
