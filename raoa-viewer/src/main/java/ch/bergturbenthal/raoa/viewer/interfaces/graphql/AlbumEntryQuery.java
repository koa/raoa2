package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.libs.model.elasticsearch.AlbumEntryData;
import ch.bergturbenthal.raoa.viewer.model.graphql.AlbumEntry;
import com.coxautodev.graphql.tools.GraphQLResolver;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class AlbumEntryQuery implements GraphQLResolver<AlbumEntry> {
  // private static final Duration TIMEOUT = Duration.ofMinutes(5);

  public AlbumEntryQuery() {}

  public CompletableFuture<String> getContentType(AlbumEntry entry) {
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

  public CompletableFuture<Instant> getCreated(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getCreateTime);
  }

  @NotNull
  public <T> CompletableFuture<T> extractDataEntryValue(
      final AlbumEntry entry, final Function<AlbumEntryData, T> function) {
    return entry.getElDataEntry().flatMap(v -> Mono.justOrEmpty(function.apply(v))).toFuture();
  }

  @NotNull
  public <T> CompletableFuture<T> extractDataEntryValue(
      final AlbumEntry entry, final Function<AlbumEntryData, T> function, T defaultValue) {
    return entry
        .getElDataEntry()
        .flatMap(v -> Mono.justOrEmpty(function.apply(v)))
        .defaultIfEmpty(defaultValue)
        .toFuture();
  }

  public CompletableFuture<Integer> getWidth(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getWidth);
  }

  public CompletableFuture<Integer> getHeight(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getHeight);
  }

  public CompletableFuture<String> getCameraModel(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getCameraModel);
  }

  public CompletableFuture<String> getCameraManufacturer(AlbumEntry entry) {

    return extractDataEntryValue(entry, AlbumEntryData::getCameraManufacturer);
  }

  public CompletableFuture<Double> getFocalLength(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getFocalLength);
  }

  public CompletableFuture<Double> getFNumber(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getFNumber);
  }

  public CompletableFuture<Integer> getTargetWidth(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getTargetWidth);
  }

  public CompletableFuture<Integer> getTargetHeight(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getTargetHeight);
  }

  public CompletableFuture<Double> getFocalLength35(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getFocalLength35);
  }

  public CompletableFuture<Double> getExposureTime(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getExposureTime);
  }

  public CompletableFuture<Integer> getIsoSpeedRatings(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getIsoSpeedRatings);
  }

  public CompletableFuture<Set<String>> getKeywords(AlbumEntry entry) {
    return extractDataEntryValue(entry, AlbumEntryData::getKeywords, Collections.emptySet());
  }
}
