package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.viewer.model.graphql.Album;
import ch.bergturbenthal.raoa.viewer.model.graphql.AlbumEntry;
import ch.bergturbenthal.raoa.viewer.model.graphql.UserReference;
import ch.bergturbenthal.raoa.viewer.service.UserManager;
import com.coxautodev.graphql.tools.GraphQLResolver;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.jgit.treewalk.filter.OrTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class AlbumQuery implements GraphQLResolver<Album> {
  private static final Duration TIMEOUT = Duration.ofMinutes(5);

  private static final TreeFilter ENTRIES_FILTER =
      OrTreeFilter.create(
          new TreeFilter[] {
            PathSuffixFilter.create(".JPG"), PathSuffixFilter.create(".JPEG"),
            PathSuffixFilter.create(".jpg"), PathSuffixFilter.create(".jpeg")
          });
  private final UserManager userManager;
  private final AlbumList albumList;

  public AlbumQuery(final UserManager userManager, final AlbumList albumList) {
    this.userManager = userManager;
    this.albumList = albumList;
  }

  public String getZipDownloadUri(Album album) {
    return album.getContext().getContexRootPath() + "/rest/album-zip/" + album.getId().toString();
  }

  public CompletableFuture<List<UserReference>> canAccessedBy(Album album) {
    if (album.getContext().canUserManageUsers()) {
      return userManager
          .listUserForAlbum(album.getId())
          .map(u -> new UserReference(u.getId(), u.getUserData(), album.getContext()))
          .collectList()
          .timeout(TIMEOUT)
          .toFuture();
    }
    return CompletableFuture.completedFuture(Collections.emptyList());
  }

  public CompletableFuture<List<AlbumEntry>> getEntries(Album album) {
    return streamEntries(album)
        .map(e -> new AlbumEntry(album, e.getFileId().name(), e.getNameString()))
        .collectList()
        .timeout(TIMEOUT)
        .toFuture();
  }

  @NotNull
  private Flux<GitAccess.GitFileEntry> streamEntries(final Album album) {
    if (album.getContext().canAccessAlbum(album.getId()))
      return albumList
          .getAlbum(album.getId())
          .flatMapMany(e -> e.listFiles(ENTRIES_FILTER))
          .timeout(TIMEOUT);
    return Flux.empty();
  }

  public CompletableFuture<String> getName(Album album) {
    return gitAccessOfAlbum(album).flatMap(GitAccess::getName).timeout(TIMEOUT).toFuture();
  }

  @NotNull
  private Mono<GitAccess> gitAccessOfAlbum(final Album album) {
    if (album.getContext().canAccessAlbum(album.getId())) {
      return albumList.getAlbum(album.getId());
    }
    return Mono.empty();
  }

  public CompletableFuture<Long> getEntryCount(Album album) {
    return streamEntries(album).count().timeout(TIMEOUT).toFuture();
  }
}
