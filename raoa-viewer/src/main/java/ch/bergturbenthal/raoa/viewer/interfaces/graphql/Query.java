package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.elastic.model.CommitJob;
import ch.bergturbenthal.raoa.elastic.repository.CommitJobRepository;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.UploadFilenameService;
import ch.bergturbenthal.raoa.viewer.interfaces.graphql.model.ImportFile;
import ch.bergturbenthal.raoa.viewer.model.graphql.Album;
import ch.bergturbenthal.raoa.viewer.model.graphql.AuthenticationState;
import ch.bergturbenthal.raoa.viewer.model.graphql.GroupReference;
import ch.bergturbenthal.raoa.viewer.model.graphql.QueryContext;
import ch.bergturbenthal.raoa.viewer.model.graphql.RegistrationRequest;
import ch.bergturbenthal.raoa.viewer.model.graphql.UserReference;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.io.File;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Controller
public class Query {
    private final QueryContextSupplier queryContextSupplier;
    private final DataViewService dataViewService;
    private final AuthorizationManager authorizationManager;
    private final AlbumList albumList;
    private final UploadFilenameService uploadFilenameService;
    private final CommitJobRepository commitJobRepository;

    public Query(final QueryContextSupplier queryContextSupplier, final DataViewService dataViewService,
            final AuthorizationManager authorizationManager, final AlbumList albumList,
            final UploadFilenameService uploadFilenameService, final CommitJobRepository commitJobRepository) {
        this.queryContextSupplier = queryContextSupplier;
        this.dataViewService = dataViewService;
        this.authorizationManager = authorizationManager;
        this.albumList = albumList;
        this.uploadFilenameService = uploadFilenameService;
        this.commitJobRepository = commitJobRepository;
    }

    @QueryMapping
    public Mono<CommitJob> pollCommitState(@Argument UUID jobId) {
        return Mono.zip(queryContextSupplier.createContext(), commitJobRepository.findById(jobId)).filterWhen(
                t -> authorizationManager.canUserAccessToAlbum(t.getT1().getSecurityContext(), t.getT2().getAlbumId()))
                .map(Tuple2::getT2);
    }

    @QueryMapping
    public Mono<Album> albumById(@Argument UUID id) {
        return queryContextSupplier.createContext().filterWhen(
                queryContext -> authorizationManager.canUserAccessToAlbum(queryContext.getSecurityContext(), id))
                .map(c -> new Album(id, c, dataViewService.readAlbum(id).cache()));
    }

    @QueryMapping()
    public Flux<RegistrationRequest> listPendingRequests() {
        return queryContextSupplier.createContext().filter(QueryContext::canUserManageUsers)
                .flatMapMany(context -> dataViewService.listAllRequestedAccess().map(r -> {
                    final RegistrationRequest.RegistrationRequestBuilder builder = RegistrationRequest.builder()
                            .authenticationId(r.getAuthenticationId()).data(r.getUserData()).reason(r.getComment());
                    if (r.getRequestedAlbum() != null) {
                        builder.requestAlbum(new Album(r.getRequestedAlbum(), context,
                                dataViewService.readAlbum(r.getRequestedAlbum()).cache()));
                    }
                    return builder.build();
                }))

        // .log("pending request")
        ;
    }

    @QueryMapping()
    public Flux<Album> listAlbums() {
        return queryContextSupplier.createContext()
                .flatMapMany(queryContext -> dataViewService.listAlbums()
                        .filterWhen(albumData -> authorizationManager
                                .canUserAccessToAlbum(queryContext.getSecurityContext(), albumData.getRepositoryId()))
                        .map(album -> new Album(album.getRepositoryId(), queryContext, Mono.just(album))));
    }

    @QueryMapping()
    public Flux<UserReference> listUsers() {
        return queryContextSupplier.createContext().filter(QueryContext::canUserManageUsers)
                .flatMapMany(queryContext -> dataViewService.listUsers()
                        .map(u -> new UserReference(u.getId(), u.getUserData(), queryContext)));
    }

    @QueryMapping()
    public Mono<UserReference> userById(@Argument UUID id) {
        return queryContextSupplier.createContext().filter(QueryContext::canUserManageUsers)
                .flatMap(queryContext -> dataViewService.findUserById(id)
                        .map(u -> new UserReference(u.getId(), u.getUserData(), queryContext)));
    }

    @QueryMapping
    public Mono<GroupReference> groupById(@Argument UUID id) {
        return queryContextSupplier.createContext().filter(QueryContext::canUserManageUsers)
                .flatMap(queryContext -> dataViewService.findGroupById(id)
                        .map(u -> new GroupReference(u.getId(), queryContext, Mono.just(u))));
    }

    @QueryMapping
    public Mono<AuthenticationState> authenticationState() {
        // log.info("Query for authentication state");
        return queryContextSupplier.createContext().flatMap(QueryContext::getAuthenticationState)
                .defaultIfEmpty(AuthenticationState.UNKNOWN);
    }

    @QueryMapping
    public Mono<UserReference> currentUser() {
        return queryContextSupplier.createContext()
                .map(u -> u.getCurrentUser().map(c -> new UserReference(c.getId(), c.getUserData(), u)))
                .filter(Optional::isPresent).map(Optional::get);
    }

    @QueryMapping
    public Flux<GroupReference> listGroups() {
        return queryContextSupplier.createContext().flatMapMany(context -> dataViewService.listGroups()
                // .log("group")
                .filter(group -> context.canAccessGroup(group.getId()))
                .map(g -> new GroupReference(g.getId(), context, Mono.just(g)))
        // .log("group-ref")
        )
        // .log("result")

        ;
    }

    @QueryMapping
    public Mono<Album> previewImport(@Argument ImportFile file) {
        return queryContextSupplier.createContext().filter(QueryContext::canUserEditData).flatMap(context -> {
            final File uploadFile = uploadFilenameService.createTempUploadFile(file.getFileId());
            if (!uploadFile.exists())
                return Mono.empty();
            return albumList.detectTargetAlbum(uploadFile.toPath())
                    .filterWhen(id -> authorizationManager.canUserModifyAlbum(context.getSecurityContext(), id))
                    .map(albumId -> new Album(albumId, context, dataViewService.readAlbum(albumId).cache()));
        }).doOnError(ex -> log.warn("Cannot commit import", ex));
    }
}
