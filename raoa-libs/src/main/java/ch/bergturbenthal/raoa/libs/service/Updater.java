package ch.bergturbenthal.raoa.libs.service;

import java.nio.file.Path;
import lombok.Builder;
import lombok.Value;
import org.eclipse.jgit.lib.ObjectId;
import reactor.core.publisher.Mono;

public interface Updater {
    Mono<ObjectId> importFile(Path file, String name);

    Mono<ObjectId> importFile(Path file, String name, boolean replaceIfExists);

    Mono<Boolean> removeFile(String name);

    Mono<Boolean> commit(CommitContext context);

    Mono<Void> close();

    @Value
    @Builder
    class CommitContext {
        String message;
        String username;
        String email;
    }
}
