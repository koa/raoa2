package ch.bergturbenthal.raoa.elastic.model;

import ch.bergturbenthal.raoa.elastic.model.serializer.ObjectIdSerializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import lombok.Builder;
import lombok.Value;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "album-data-1")
@Value
@Builder
@JsonDeserialize(builder = AlbumData.AlbumDataBuilder.class)
public class AlbumData {
  @Id UUID repositoryId;

  @Field(type = FieldType.Keyword)
  @JsonSerialize(using = ObjectIdSerializer.class)
  ObjectId currentVersion;

  @Field(type = FieldType.Text)
  String name;

  @Field(type = FieldType.Integer)
  int entryCount;

  @Nullable
  @Field(type = FieldType.Double)
  Instant createTime;

  @Field(type = FieldType.Object)
  List<KeywordCount> keywordCount;

  @Field(type = FieldType.Object)
  Map<String, String> labels;

  @JsonPOJOBuilder(withPrefix = "")
  public static class AlbumDataBuilder {}
}
