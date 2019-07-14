package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import graphql.language.Field;
import graphql.schema.DataFetcher;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GraphQLDataFetchers {
  @Autowired private AlbumList albumList;

  private static Map<String, BiFunction<Supplier<GitAccess>, Field, Object>> albumExtractors =
      new HashMap<>();

  {
    albumExtractors.put(
        "id", (gitAccess, field) -> gitAccess.get().getMetadata().getAlbumId().toString());
    albumExtractors.put("name", (gitAccess, field) -> gitAccess.get().getName());
    albumExtractors.put("entries", (gitAccessSupplier, field) -> null);
  }

  public DataFetcher albumByIdFetcher() {
    return dataFetchingEnvironment -> {
      final String albumId = dataFetchingEnvironment.getArgument("id");

      final UUID uuid = UUID.fromString(albumId);

      Supplier<GitAccess> gitAccessSupplier = () -> albumList.getAlbum(uuid).orElseThrow();

      Map<String, Object> ret = new HashMap<>();
      for (Field field : dataFetchingEnvironment.getFields()) {
        final String name = field.getName();
        final Object value = albumExtractors.get(name).apply(gitAccessSupplier, field);
        ret.put(name, value);
      }
      return ret;
    };
  }

  public DataFetcher listEntriesFetcher() {
    return dataFetchingEnvironment -> {
      return null;
    };
  }
}
