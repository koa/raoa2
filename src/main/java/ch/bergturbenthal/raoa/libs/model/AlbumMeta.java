package ch.bergturbenthal.raoa.libs.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
@JsonDeserialize(builder = AlbumMeta.AlbumMetaBuilder.class)
public class AlbumMeta {
  private UUID albumId;
  private String albumTitle;
  private String titleEntry;

  @JsonPOJOBuilder(withPrefix = "")
  public static class AlbumMetaBuilder {}
}
