package ch.bergturbenthal.raoa.elastic.model;

import ch.bergturbenthal.raoa.elastic.model.serializer.ObjectIdSerializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.eclipse.jgit.lib.ObjectId;
import org.elasticsearch.core.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

@Document(indexName = "album-data-2")
@Setting(shards = 3)
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

  @Field(type = FieldType.Keyword)
  String titleEntry;

  @Field(type = FieldType.Keyword)
  @JsonSerialize(using = ObjectIdSerializer.class)
  ObjectId titleEntryId;

  @JsonPOJOBuilder(withPrefix = "")
  public static class AlbumDataBuilder {}
}
