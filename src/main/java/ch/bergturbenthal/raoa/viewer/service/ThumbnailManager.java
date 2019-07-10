package ch.bergturbenthal.raoa.viewer.service;

import java.io.File;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

public interface ThumbnailManager {
  Mono<File> takeThumbnail(ObjectId id, ObjectLoader reader, MediaType mediaType);
}
