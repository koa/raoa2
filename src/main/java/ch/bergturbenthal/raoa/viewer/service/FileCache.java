package ch.bergturbenthal.raoa.viewer.service;

import java.io.File;
import org.eclipse.jgit.lib.ObjectId;
import reactor.core.publisher.Mono;

public interface FileCache {
  Mono<File> take(ObjectId objectId);
}
