package ch.bergturbenthal.raoa.coordinator.service;

import ch.bergturbenthal.raoa.elastic.model.AlbumEntryData;
import ch.bergturbenthal.raoa.libs.model.ProcessImageRequest;
import org.eclipse.jgit.lib.ObjectId;
import reactor.core.publisher.Mono;

public interface RemoteImageProcessor {
    Mono<AlbumEntryData> processImage(ObjectId fileId, ProcessImageRequest data);
}
