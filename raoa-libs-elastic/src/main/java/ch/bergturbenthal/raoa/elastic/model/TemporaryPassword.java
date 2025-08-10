package ch.bergturbenthal.raoa.elastic.model;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "temp_password_1", createIndex = true)
@Value
@Builder(toBuilder = true)
public class TemporaryPassword {
    @Id
    @Field(type = FieldType.Keyword)
    String id;

    @Field(type = FieldType.Keyword)
    UUID userId;

    @Field(type = FieldType.Keyword)
    String title;

    @Field(type = FieldType.Keyword)
    String password;

    @Field(type = FieldType.Double)
    Instant validUntil;
}
