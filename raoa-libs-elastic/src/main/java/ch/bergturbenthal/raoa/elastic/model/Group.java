package ch.bergturbenthal.raoa.elastic.model;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "group", createIndex = true)
@Value
@Builder(toBuilder = true)
public class Group {
  @Id UUID id;

  @Field(type = FieldType.Text)
  String name;

  @Field(type = FieldType.Keyword)
  Set<UUID> visibleAlbums;

  @Field(type = FieldType.Object)
  Map<String, String> labels;
}
