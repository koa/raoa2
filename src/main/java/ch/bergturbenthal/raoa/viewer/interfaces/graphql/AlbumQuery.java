package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.viewer.model.graphql.Album;
import ch.bergturbenthal.raoa.viewer.model.graphql.AlbumEntry;
import com.coxautodev.graphql.tools.GraphQLResolver;
import java.io.IOException;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Stream;
import org.eclipse.jgit.treewalk.filter.OrTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

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
    return () ->
        streamEntries(album)
            .map(e -> new AlbumEntry(album, e.getFileId().name(), e.getNameString()))
            .iterator();
  }

  @NotNull
  private Stream<GitAccess.GitFileEntry> streamEntries(final Album album) {
    return albumList
        .getAlbum(album.getId())
        .map(
            e -> {
              try {
                return e.listFiles(ENTRIES_FILTER);
              } catch (IOException ex) {
                throw new RuntimeException("Cannot list album " + album, ex);
              }
            })
        .map(Collection::stream)
        .orElse(Stream.empty());
  }

  public Optional<String> getName(Album album) {
    return albumList.getAlbum(album.getId()).map(GitAccess::getName);
  }

  public int getEntryCount(Album album) {
    return (int) streamEntries(album).count();
  }
}
