package ch.bergturbenthal.raoa.viewer.model.elasticsearch;

import ch.bergturbenthal.raoa.viewer.model.elasticsearch.serializer.ObjectIdSerializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "album-data")
@Value
@Builder
@JsonDeserialize(builder = AlbumData.AlbumDataBuilder.class)
public class AlbumData {
  @Id private UUID repositoryId;

  @Field(type = FieldType.Keyword)
  @JsonSerialize(using = ObjectIdSerializer.class)
  private ObjectId currentVersion;

  @Field(type = FieldType.Text)
  private String name;

  @Field(type = FieldType.Integer)
  private int entryCount;

  @Field(type = FieldType.Double)
  private Instant createTime;

  @JsonPOJOBuilder(withPrefix = "")
  public static class AlbumDataBuilder {}
}
