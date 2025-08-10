package ch.bergturbenthal.raoa.libs.service;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import lombok.Value;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AlbumList {

    void resetCache();

    FileImporter createImporter(final Updater.CommitContext context);

    Flux<FoundAlbum> listAlbums();

    Flux<String> listParentDirs();

    Mono<GitAccess> getAlbum(UUID albumId);

    Mono<UUID> createAlbum(List<String> albumPath);

    Mono<UUID> detectTargetAlbum(Path file);

    @Value
    class FoundAlbum {
        UUID albumId;
        GitAccess access;
    }
}
