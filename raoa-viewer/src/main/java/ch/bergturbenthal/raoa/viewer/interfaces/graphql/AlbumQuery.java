package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.viewer.model.graphql.Album;
import ch.bergturbenthal.raoa.viewer.model.graphql.AlbumEntry;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import com.coxautodev.graphql.tools.GraphQLResolver;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.eclipse.jgit.treewalk.filter.OrTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class AlbumQuery implements GraphQLResolver<Album> {
  private static final TreeFilter ENTRIES_FILTER =
      OrTreeFilter.create(
          new TreeFilter[] {
            PathSuffixFilter.create(".JPG"), PathSuffixFilter.create(".JPEG"),
            PathSuffixFilter.create(".jpg"), PathSuffixFilter.create(".jpeg")
          });
  private final AuthorizationManager authorizationManager;
  private final AlbumList albumList;

  public AlbumQuery(final AuthorizationManager authorizationManager, final AlbumList albumList) {
    this.authorizationManager = authorizationManager;
    this.albumList = albumList;
  }

  public CompletableFuture<List<AlbumEntry>> getEntries(Album album) {
    return monoIfUserCanAccessAlbum(
            SecurityContextHolder.getContext(),
            album,
            a ->
                streamEntries(a)
                    .map(e -> new AlbumEntry(a, e.getFileId().name(), e.getNameString()))
                    .collectList())
        .toFuture();
  }

  private <T> Mono<T> monoIfUserCanAccessAlbum(
      SecurityContext context, Album id, Function<Album, Mono<T>> creator) {
    return authorizationManager
        .canUserAccessToAlbum(context, id.getId())
        .filter(t -> t)
        .flatMap(t -> creator.apply(id));
  }

  private <T> Mono<T> ifUserCanAccessAlbum(
      SecurityContext context, Album id, Function<Album, T> creator) {
    return authorizationManager
        .canUserAccessToAlbum(context, id.getId())
        .filter(t -> t)
        .map(t -> creator.apply(id));
  }

  private <T> Flux<T> fluxIfUserCanAccessAlbum(
      SecurityContext context, Album id, Function<Album, Flux<T>> creator) {
    return authorizationManager
        .canUserAccessToAlbum(context, id.getId())
        .filter(t -> t)
        .flatMapMany(t -> creator.apply(id));
  }

  @NotNull
  private Flux<GitAccess.GitFileEntry> streamEntries(final Album album) {
    return albumList.getAlbum(album.getId()).flatMapMany(e -> e.listFiles(ENTRIES_FILTER));
  }

  public CompletableFuture<String> getName(Album album) {
    final SecurityContext context = SecurityContextHolder.getContext();
    return gitAccessOfAlbum(context, album).flatMap(GitAccess::getName).toFuture();
  }

  @NotNull
  public Mono<GitAccess> gitAccessOfAlbum(final SecurityContext context, final Album album) {
    return ifUserCanAccessAlbum(context, album, Album::getId).flatMap(albumList::getAlbum);
  }

  public CompletableFuture<Long> getEntryCount(Album album) {

    return monoIfUserCanAccessAlbum(
            SecurityContextHolder.getContext(), album, a -> streamEntries(a).count())
        .toFuture();
  }
}
