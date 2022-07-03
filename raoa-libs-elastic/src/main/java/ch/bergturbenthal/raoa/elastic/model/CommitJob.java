package ch.bergturbenthal.raoa.elastic.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Value
@Builder(toBuilder = true)
@Document(indexName = "commit-job-v1")
public class CommitJob {
  @Id UUID commitJobId;

  @Field(type = FieldType.Keyword)
  UUID albumId;

  @Field(type = FieldType.Keyword)
  State currentPhase;

  @Field(type = FieldType.Integer)
  int totalStepCount;

  @Field(type = FieldType.Integer)
  int currentStep;

  @Field(type = FieldType.Object)
  List<ImportFile> files;

  @Field(type = FieldType.Keyword)
  String username;

  @Field(type = FieldType.Keyword)
  String usermail;

  @Field(type = FieldType.Double)
  Instant lastModified;

  public enum State {
    READY,
    ADD_FILES,
    WRITE_TREE,
    DONE,
    FAILED
  }

  @Value
  public static class ImportFile {
    UUID fileId;
    String filename;
    long size;
  }
}
