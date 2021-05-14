package ch.bergturbenthal.raoa.libs.service;

import ch.bergturbenthal.raoa.libs.model.AlbumMeta;
import com.adobe.xmp.XMPMeta;
import java.time.Instant;
import java.util.Collection;
import java.util.function.Function;
import lombok.Value;
import org.apache.tika.metadata.Metadata;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface GitAccess {
  Flux<GitFileEntry> listFiles(TreeFilter filter);

  Mono<ObjectLoader> readObject(AnyObjectId fileId);

  Mono<String> filenameOfObject(AnyObjectId objectId);

  Mono<ObjectLoader> readObject(String filename);

  Mono<ObjectId> getCurrentVersion();

  Flux<Instant> readAutoadd();

  Mono<Boolean> updateAutoadd(Collection<Instant> autoaddTimes, Updater.CommitContext context);

  Mono<Updater> createUpdater();

  Mono<String> getName();

  Mono<String> getFullPath();

  Mono<AlbumMeta> getMetadata();

  Mono<Boolean> updateMetadata(
      Function<AlbumMeta, AlbumMeta> mutation, final Updater.CommitContext context);

  Mono<Metadata> entryMetdata(AnyObjectId entryId);

  Mono<XMPMeta> readXmpMeta(ObjectLoader loader);

  Mono<Boolean> writeXmpMeta(String filename, XMPMeta xmpMeta, final Updater.CommitContext context);

  Mono<Repository> getRepository();

  @Value
  class GitFileEntry {
    String nameString;
    FileMode fileMode;
    ObjectId fileId;
  }
}
