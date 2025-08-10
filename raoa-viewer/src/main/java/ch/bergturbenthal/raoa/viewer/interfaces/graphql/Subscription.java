package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.elastic.model.AlbumData;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.viewer.interfaces.graphql.model.AlbumUpdatedEvent;
import ch.bergturbenthal.raoa.viewer.model.graphql.Album;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import graphql.GraphQLContext;
import java.time.Duration;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

@Slf4j
@Controller
public class Subscription {
    private final QueryContextSupplier queryContextSupplier;
    private final DataViewService dataViewService;
    private final AuthorizationManager authorizationManager;
    private final Flux<AlbumData> albumPollerFlux;

    public Subscription(final QueryContextSupplier queryContextSupplier, final DataViewService dataViewService,
            final AuthorizationManager authorizationManager) {
        this.queryContextSupplier = queryContextSupplier;
        this.dataViewService = dataViewService;
        this.authorizationManager = authorizationManager;
        albumPollerFlux = Flux.interval(Duration.ofSeconds(30))
                .flatMapSequential(interval -> dataViewService.listAlbums());
    }

    @SubscriptionMapping
    public Flux<AlbumUpdatedEvent> albumModified(GraphQLContext context) {
        log.info("Graphql Context: " + context);
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        log.info("Authentication: " + auth);

        return queryContextSupplier.createContext().flatMapMany(queryContext -> {
            log.info("User: " + queryContext.getCurrentUser());
            Map<UUID, ObjectId> lastShownVersion = Collections.synchronizedMap(new HashMap<>());
            return Flux.merge(dataViewService.listAlbums(), albumPollerFlux)
                    .flatMap(album -> authorizationManager
                            .canUserAccessToAlbum(queryContext.getSecurityContext(), album.getRepositoryId())
                            .map(canAccess -> Tuples.of(album, canAccess)))
                    .<Optional<AlbumUpdatedEvent>> map(t -> {
                        final AlbumData album = t.getT1();
                        final Boolean visible = t.getT2();
                        final UUID repositoryId = album.getRepositoryId();
                        if (visible) {
                            final ObjectId versionBefore = lastShownVersion.put(repositoryId,
                                    album.getCurrentVersion());
                            if (versionBefore == null)
                                return Optional.of(new AlbumUpdatedEvent(AlbumUpdatedEvent.AlbumUpdateEventType.ADDED,
                                        repositoryId, new Album(repositoryId, queryContext, Mono.just(album))));
                            else if (versionBefore.equals(album.getCurrentVersion())) {
                                return Optional.empty();
                            } else {
                                return Optional
                                        .of(new AlbumUpdatedEvent(AlbumUpdatedEvent.AlbumUpdateEventType.MODIFIED,
                                                repositoryId, new Album(repositoryId, queryContext, Mono.just(album))));
                            }

                        } else {
                            final ObjectId wasVisible = lastShownVersion.remove(repositoryId);
                            if (wasVisible != null) {
                                return Optional.of(new AlbumUpdatedEvent(AlbumUpdatedEvent.AlbumUpdateEventType.REMOVED,
                                        repositoryId, null));
                            } else
                                return Optional.empty();
                        }
                    });
        }).filter(Optional::isPresent).map(Optional::get)
        // .log("subscription")
        ;
    }
}
