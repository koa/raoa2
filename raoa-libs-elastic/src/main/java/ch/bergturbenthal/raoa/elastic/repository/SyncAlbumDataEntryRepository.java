package ch.bergturbenthal.raoa.elastic.repository;

import ch.bergturbenthal.raoa.elastic.model.AlbumEntryData;
import org.springframework.data.repository.CrudRepository;

public interface SyncAlbumDataEntryRepository extends CrudRepository<AlbumEntryData, String> {}
