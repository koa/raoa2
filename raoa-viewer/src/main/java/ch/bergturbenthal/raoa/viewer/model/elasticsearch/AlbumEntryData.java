package ch.bergturbenthal.raoa.viewer.model.elasticsearch;

import ch.bergturbenthal.raoa.viewer.model.elasticsearch.serializer.ObjectIdSerializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Slf4j
@Document(indexName = "album-entry")
@Value
@JsonDeserialize(builder = AlbumEntryData.AlbumEntryDataBuilder.class)
public class AlbumEntryData {

  @Builder
  public AlbumEntryData(
      final UUID albumId,
      final ObjectId entryId,
      final Integer width,
      final Integer height,
      final Integer targetWidth,
      final Integer targetHeight,
      final String filename,
      final Instant createTime,
      final String cameraModel,
      final String cameraManufacturer,
      final Integer focalLength,
      final Double fNumber,
      final String contentType) {
    this.albumId = albumId;
    this.entryId = entryId;
    this.width = width;
    this.height = height;
    this.targetWidth = targetWidth;
    this.targetHeight = targetHeight;
    this.filename = filename;
    this.createTime = createTime;
    this.cameraModel = cameraModel;
    this.cameraManufacturer = cameraManufacturer;
    this.focalLength = focalLength;
    this.fNumber = fNumber;
    this.contentType = contentType;
    this.documentId = createDocumentId(albumId, entryId);
  }

  @NotNull
  public static String createDocumentId(final UUID albumId, final ObjectId entryId) {
    return albumId + "-" + entryId.name();
  }

  @Id
  @Field(index = false)
  private String documentId;

  @Field(type = FieldType.Keyword)
  private UUID albumId;

  @Field(type = FieldType.Keyword)
  @JsonSerialize(using = ObjectIdSerializer.class)
  private ObjectId entryId;

  @Field(type = FieldType.Integer)
  private Integer width;

  @Field(type = FieldType.Integer)
  private Integer height;

  @Field(type = FieldType.Integer)
  private Integer targetWidth;

  @Field(type = FieldType.Integer)
  private Integer targetHeight;

  @Field(type = FieldType.Keyword)
  private String filename;

  @Field(type = FieldType.Double)
  private Instant createTime;

  @Field(type = FieldType.Keyword)
  private String cameraModel;

  @Field(type = FieldType.Keyword)
  private String cameraManufacturer;

  @Field(type = FieldType.Integer)
  private Integer focalLength;

  @Field(type = FieldType.Double)
  private Double fNumber;

  @Field(type = FieldType.Keyword)
  private String contentType;

  @JsonPOJOBuilder(withPrefix = "")
  public static class AlbumEntryDataBuilder {}
}
