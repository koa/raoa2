package ch.bergturbenthal.raoa.libs.repository;

import ch.bergturbenthal.raoa.libs.model.elasticsearch.AlbumEntryData;
import org.springframework.data.repository.CrudRepository;

public interface SyncAlbumDataEntryRepository extends CrudRepository<AlbumEntryData, String> {}
