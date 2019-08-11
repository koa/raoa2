package ch.bergturbenthal.raoa.libs.service;

import ch.bergturbenthal.raoa.libs.model.AlbumMeta;
import java.time.Instant;
import lombok.Value;
import org.apache.tika.metadata.Metadata;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface GitAccess {
  Flux<GitFileEntry> listFiles(TreeFilter filter);

  Mono<ObjectLoader> readObject(AnyObjectId fileId);

  Mono<ObjectLoader> readObject(String filename);

  Flux<Instant> readAutoadd();

  Mono<Updater> createUpdater();

  Mono<String> getName();

  Mono<String> getFullPath();

  Mono<AlbumMeta> getMetadata();

  Mono<Metadata> entryMetdata(AnyObjectId entryId);

  @Value
  class GitFileEntry {
    final String nameString;
    final FileMode fileMode;
    final ObjectId fileId;
  }
}
