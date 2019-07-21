package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.viewer.model.graphql.Album;
import com.coxautodev.graphql.tools.GraphQLQueryResolver;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class Query implements GraphQLQueryResolver {
  private final AlbumList albumList;

  public Query(final AlbumList albumList) {
    this.albumList = albumList;
  }

  public Album getAlbumById(UUID albumId) {
    return new Album(albumId);
  }

  public Iterable<Album> listAlbums() {
    return () ->
        albumList
            .listAlbums()
            .map(AlbumList.FoundAlbum::getAlbumId)
            .map(this::getAlbumById)
            .iterator();
  }
}
