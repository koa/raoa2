package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.elastic.model.CommitJob;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.viewer.model.graphql.Album;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

@Slf4j
@Controller
public class QueryCommitJob {
    private static final String TYPE_NAME = "CommitJob";
    private final QueryContextSupplier queryContextSupplier;
    private final AuthorizationManager authorizationManager;
    private final DataViewService dataViewService;

    public QueryCommitJob(final QueryContextSupplier queryContextSupplier,
            final AuthorizationManager authorizationManager, final DataViewService dataViewService) {
        this.queryContextSupplier = queryContextSupplier;
        this.authorizationManager = authorizationManager;
        this.dataViewService = dataViewService;
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public Mono<Album> album(CommitJob job) {
        return queryContextSupplier.createContext()
                .filterWhen(queryContext -> authorizationManager.canUserAccessToAlbum(queryContext.getSecurityContext(),
                        job.getAlbumId()))
                .map(c -> new Album(job.getAlbumId(), c, dataViewService.readAlbum(job.getAlbumId()).cache()));
    }
}
