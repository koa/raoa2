package ch.bergturbenthal.raoa.libs.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
@JsonDeserialize(builder = AlbumMeta.AlbumMetaBuilder.class)
public class AlbumMeta {
  UUID albumId;
  String albumTitle;
  String titleEntry;
  Map<String, String> labels;

  @JsonPOJOBuilder(withPrefix = "")
  public static class AlbumMetaBuilder {}
}
