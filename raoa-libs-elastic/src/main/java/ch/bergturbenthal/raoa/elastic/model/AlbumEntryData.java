package ch.bergturbenthal.raoa.elastic.model;

import ch.bergturbenthal.raoa.elastic.model.serializer.ObjectIdSerializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;

@Slf4j
@Document(indexName = "album-entry-1")
@Setting(shards = 3)
@Value
@JsonDeserialize(builder = AlbumEntryData.AlbumEntryDataBuilder.class)
public class AlbumEntryData {
  @Id
  @Field(index = false)
  String documentId;

  @Field(type = FieldType.Keyword)
  UUID albumId;

  @Field(type = FieldType.Keyword)
  @JsonSerialize(using = ObjectIdSerializer.class)
  ObjectId entryId;

  @Field(type = FieldType.Keyword)
  @JsonSerialize(using = ObjectIdSerializer.class)
  ObjectId xmpFileId;

  @Field(type = FieldType.Integer)
  Integer width;

  @Field(type = FieldType.Integer)
  Integer height;

  @Field(type = FieldType.Integer)
  Integer targetWidth;

  @Field(type = FieldType.Integer)
  Integer targetHeight;

  @Field(type = FieldType.Keyword)
  String filename;

  @Field(type = FieldType.Double)
  Instant createTime;

  @Field(type = FieldType.Keyword)
  String cameraModel;

  @Field(type = FieldType.Keyword)
  String cameraManufacturer;

  @Field(type = FieldType.Double)
  Double focalLength;

  @Field(type = FieldType.Double)
  Double focalLength35;

  @Field(type = FieldType.Double)
  Double fNumber;

  @Field(type = FieldType.Double)
  Double exposureTime;

  @Field(type = FieldType.Integer)
  Integer isoSpeedRatings;

  @Field(type = FieldType.Keyword)
  String contentType;

  @Field(type = FieldType.Keyword)
  Set<String> keywords;

  @Field(type = FieldType.Text)
  String description;

  @Field(type = FieldType.Integer)
  Integer rating;

  @Field GeoPoint captureCoordinates;

  @Builder(toBuilder = true)
  public AlbumEntryData(
      final String documentId,
      final UUID albumId,
      final ObjectId entryId,
      final ObjectId xmpFileId,
      final Integer width,
      final Integer height,
      final Integer targetWidth,
      final Integer targetHeight,
      final String filename,
      final Instant createTime,
      final String cameraModel,
      final String cameraManufacturer,
      final Double focalLength,
      final Double focalLength35,
      final Double fNumber,
      final Double exposureTime,
      final Integer isoSpeedRatings,
      final String contentType,
      final Set<String> keywords,
      final String description,
      final Integer rating,
      final GeoPoint captureCoordinates) {
    this.albumId = albumId;
    this.entryId = entryId;
    this.xmpFileId = xmpFileId;
    this.width = width;
    this.height = height;
    this.targetWidth = targetWidth;
    this.targetHeight = targetHeight;
    this.filename = filename;
    this.createTime = createTime;
    this.cameraModel = cameraModel;
    this.cameraManufacturer = cameraManufacturer;
    this.focalLength = focalLength;
    this.focalLength35 = focalLength35;
    this.fNumber = fNumber;
    this.exposureTime = exposureTime;
    this.isoSpeedRatings = isoSpeedRatings;
    this.contentType = contentType;
    this.keywords = keywords;
    this.description = description;
    this.rating = rating;
    this.captureCoordinates = captureCoordinates;
    this.documentId = createDocumentId(albumId, entryId);
  }

  public static String createDocumentId(final UUID albumId, final ObjectId entryId) {
    return albumId + "-" + entryId.name();
  }

  @JsonPOJOBuilder(withPrefix = "")
  public static class AlbumEntryDataBuilder {}
}
