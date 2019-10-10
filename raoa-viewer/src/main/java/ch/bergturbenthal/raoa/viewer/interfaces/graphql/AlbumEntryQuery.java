package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.viewer.model.elasticsearch.AlbumEntryData;
import ch.bergturbenthal.raoa.viewer.model.graphql.AlbumEntry;
import com.coxautodev.graphql.tools.GraphQLResolver;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Component;

@Component
public class AlbumEntryQuery implements GraphQLResolver<AlbumEntry> {
  // private static final Duration TIMEOUT = Duration.ofMinutes(5);

  public AlbumEntryQuery() {}

  public CompletableFuture<String> getContentType(AlbumEntry entry) {
    return entry.getElDataEntry().map(AlbumEntryData::getContentType).toFuture();
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
    return entry.getElDataEntry().map(AlbumEntryData::getCreateTime).toFuture();
  }

  public CompletableFuture<Integer> getWidth(AlbumEntry entry) {
    return entry.getElDataEntry().map(AlbumEntryData::getWidth).toFuture();
  }

  public CompletableFuture<Integer> getHeight(AlbumEntry entry) {
    return entry.getElDataEntry().map(AlbumEntryData::getHeight).toFuture();
  }

  public CompletableFuture<String> getCameraModel(AlbumEntry entry) {
    return entry.getElDataEntry().map(AlbumEntryData::getCameraModel).toFuture();
  }

  public CompletableFuture<String> getCameraManufacturer(AlbumEntry entry) {
    return entry.getElDataEntry().map(AlbumEntryData::getCameraManufacturer).toFuture();
  }

  public CompletableFuture<Integer> getFocalLength(AlbumEntry entry) {
    return entry.getElDataEntry().map(AlbumEntryData::getFocalLength).toFuture();
  }

  public CompletableFuture<Double> getFNumber(AlbumEntry entry) {
    return entry.getElDataEntry().map(AlbumEntryData::getFNumber).toFuture();
  }

  public CompletableFuture<Integer> getTargetWidth(AlbumEntry entry) {
    return entry.getElDataEntry().map(AlbumEntryData::getTargetWidth).toFuture();
  }

  public CompletableFuture<Integer> getTargetHeight(AlbumEntry entry) {
    return entry.getElDataEntry().map(AlbumEntryData::getTargetHeight).toFuture();
  }
}
