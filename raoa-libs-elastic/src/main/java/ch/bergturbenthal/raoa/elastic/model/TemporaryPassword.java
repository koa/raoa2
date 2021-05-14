package ch.bergturbenthal.raoa.elastic.model;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "temp_password", createIndex = true)
@Value
@Builder(toBuilder = true)
public class TemporaryPassword {
  @Id UUID userId;

  @Field(type = FieldType.Keyword)
  String password;

  @Field(type = FieldType.Double)
  Instant validUntil;
}
