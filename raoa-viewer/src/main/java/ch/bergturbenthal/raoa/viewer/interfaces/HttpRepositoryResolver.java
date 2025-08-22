package ch.bergturbenthal.raoa.viewer.interfaces;

import ch.bergturbenthal.raoa.elastic.model.User;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.security.Principal;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

@Slf4j
@Service
public class HttpRepositoryResolver
        implements RepositoryResolver<HttpServletRequest>, ReceivePackFactory<HttpServletRequest> {
    private final Map<Repository, UUID> reverseMap = Collections.synchronizedMap(new WeakHashMap<>());
    private final AlbumList albumList;
    private final AuthorizationManager authorizationManager;

    public HttpRepositoryResolver(final AlbumList albumList, final AuthorizationManager authorizationManager) {
        this.albumList = albumList;
        this.authorizationManager = authorizationManager;
    }

    private static ReceivePack createFor(final Repository db, final String user, String email) {
        final ReceivePack rp = new ReceivePack(db);
        rp.setRefLogIdent(new PersonIdent(user, email));
        return rp;
    }

    @Override
    public Repository open(final HttpServletRequest req, final String name) throws ServiceNotAuthorizedException {
        User user = extractUser(req);

        final UUID albumId = UUID.fromString(name);

        if (user == null) {
            throw new ServiceNotAuthorizedException("Invalid user / password");
        }

        final Repository repository = albumList.getAlbum(albumId).flatMap(GitAccess::getRepository)
                .block(Duration.ofSeconds(10));
        reverseMap.put(repository, albumId);
        return repository;
    }

    private User extractUser(final HttpServletRequest req) {
        final Principal userPrincipal = req.getUserPrincipal();
        if (userPrincipal == null)
            return null;
        return (User) ((UsernamePasswordAuthenticationToken) userPrincipal).getDetails();
    }

    @Override
    public ReceivePack create(HttpServletRequest req, Repository db)
            throws ServiceNotAuthorizedException, ServiceNotEnabledException {
        if (!reverseMap.containsKey(db)) {
            log.info("Repository not cached {}", db.getDirectory());
            for (final Tuple2<UUID, Repository> entry : albumList.listAlbums()
                    .flatMap(a -> a.getAccess().getRepository().map(repo -> Tuples.of(a.getAlbumId(), repo)))
                    .toIterable()) {
                reverseMap.put(entry.getT2(), entry.getT1());
            }
        }
        final UUID albumId = reverseMap.get(db);
        if (albumId == null) {
            log.error("No album found for {}", db);
            throw new ServiceNotEnabledException("Repository not found");
        }

        final User user = extractUser(req);

        if (user != null && authorizationManager.canUserModifyAlbum(Mono.just(user), albumId).defaultIfEmpty(false)
                .block(Duration.ofSeconds(30)))
            return createFor(db, user.getUserData().getName(), user.getUserData().getEmail());
        throw new ServiceNotAuthorizedException();
    }
}
