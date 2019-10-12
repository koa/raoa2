package ch.bergturbenthal.raoa.viewer.repository;

import ch.bergturbenthal.raoa.viewer.model.elasticsearch.AlbumEntryData;
import org.springframework.data.repository.CrudRepository;

public interface SyncAlbumDataEntryRepository extends CrudRepository<AlbumEntryData, String> {}
