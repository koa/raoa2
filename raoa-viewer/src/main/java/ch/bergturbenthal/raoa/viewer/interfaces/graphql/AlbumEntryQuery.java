package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.elastic.model.AlbumEntryData;
import ch.bergturbenthal.raoa.viewer.model.graphql.AlbumEntry;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

@Controller
public class AlbumEntryQuery {
  private static final String TYPE_NAME = "AlbumEntry";

  public AlbumEntryQuery() {}

  @SchemaMapping(typeName = TYPE_NAME)
  public String contentType(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getContentType);
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public String entryUri(AlbumEntry entry) {
    return entry.getAlbum().getContext().getContexRootPath()
        + "/rest/album/"
        + entry.getAlbum().getId().toString()
        + "/"
        + entry.getId();
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public String thumbnailUri(AlbumEntry entry) {
    return entryUri(entry) + "/thumbnail";
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public String originalUri(AlbumEntry entry) {
    return entryUri(entry) + "/original";
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public OffsetDateTime created(AlbumEntry entry) {
    return Optional.ofNullable(extractDataEntryValue(entry, AlbumEntryData::getCreateTime))
        .map(i -> i.atOffset(ZoneOffset.UTC))
        .orElse(null);
  }

  public <T> T extractDataEntryValue(
      final AlbumEntry entry, final Function<AlbumEntryData, T> function) {
    return function.apply(entry.getElDataEntry());
  }

  public <T> T extractDataEntryValue(
      final AlbumEntry entry, final Function<AlbumEntryData, T> function, T defaultValue) {
    final T result = function.apply(entry.getElDataEntry());
    if (result == null) return defaultValue;
    return result;
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public Integer width(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getWidth);
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public Integer height(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getHeight);
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public String cameraModel(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getCameraModel);
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public String cameraManufacturer(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getCameraManufacturer);
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public Double focalLength(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getFocalLength);
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public Double fNumber(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getFNumber);
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public Integer targetWidth(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getTargetWidth);
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public Integer targetHeight(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getTargetHeight);
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public Double focalLength35(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getFocalLength35);
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public Double exposureTime(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getExposureTime);
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public Integer isoSpeedRatings(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getIsoSpeedRatings);
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public Set<String> keywords(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getKeywords, Collections.emptySet());
  }

  @SchemaMapping(typeName = TYPE_NAME)
  public String name(AlbumEntry entry) {
    return entry.getElDataEntry().getFilename();
  }
}
