package ch.bergturbenthal.raoa.elastic.model;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Value
@Builder(toBuilder = true)
@Document(indexName = "uploaded-file-v1")
public class UploadedFile {
    @Id
    UUID fileId;

    @Field(type = FieldType.Keyword)
    String filename;

    @Field(type = FieldType.Keyword)
    UUID uploadedUser;

    @Field(type = FieldType.Double)
    Instant uploadTime;

    @Field(type = FieldType.Keyword)
    UUID suggestedAlbum;
}
