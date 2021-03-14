package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.elastic.model.AlbumEntryData;
import ch.bergturbenthal.raoa.viewer.model.graphql.AlbumEntry;
import com.coxautodev.graphql.tools.GraphQLResolver;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class AlbumEntryQuery implements GraphQLResolver<AlbumEntry> {
  // private static final Duration TIMEOUT = Duration.ofMinutes(5);

  public AlbumEntryQuery() {}

  public String getContentType(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getContentType);
  }

  public String getEntryUri(AlbumEntry entry) {
    return entry.getAlbum().getContext().getContexRootPath()
        + "/rest/album/"
        + entry.getAlbum().getId().toString()
        + "/"
        + entry.getId();
  }

  public String getThumbnailUri(AlbumEntry entry) {
    return getEntryUri(entry) + "/thumbnail";
  }

  public String getOriginalUri(AlbumEntry entry) {
    return getEntryUri(entry) + "/original";
  }

  public Instant getCreated(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getCreateTime);
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

  public Integer getWidth(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getWidth);
  }

  public Integer getHeight(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getHeight);
  }

  public String getCameraModel(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getCameraModel);
  }

  public String getCameraManufacturer(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getCameraManufacturer);
  }

  public Double getFocalLength(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getFocalLength);
  }

  public Double getFNumber(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getFNumber);
  }

  public Integer getTargetWidth(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getTargetWidth);
  }

  public Integer getTargetHeight(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getTargetHeight);
  }

  public Double getFocalLength35(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getFocalLength35);
  }

  public Double getExposureTime(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getExposureTime);
  }

  public Integer getIsoSpeedRatings(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getIsoSpeedRatings);
  }

  public Set<String> getKeywords(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getKeywords, Collections.emptySet());
  }
}
