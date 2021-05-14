package ch.bergturbenthal.raoa.viewer.interfaces;

import ch.bergturbenthal.raoa.elastic.model.User;
import ch.bergturbenthal.raoa.elastic.service.impl.ElasticSearchDataViewService;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import java.time.Duration;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.map.LRUMap;
import org.apache.cxf.common.util.WeakIdentityHashMap;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class HttpRepositoryResolver
    implements RepositoryResolver<HttpServletRequest>, ReceivePackFactory<HttpServletRequest> {
  private final Map<String, Repository> repositoryCache =
      Collections.synchronizedMap(new LRUMap<>(20));
  private final Map<Repository, UUID> reverseMap =
      Collections.synchronizedMap(new WeakIdentityHashMap<>());
  private final AlbumList albumList;
  private final ElasticSearchDataViewService elasticSearchDataViewService;
  private final AuthorizationManager authorizationManager;

  public HttpRepositoryResolver(
      final AlbumList albumList,
      final ElasticSearchDataViewService elasticSearchDataViewService,
      final AuthorizationManager authorizationManager) {
    this.albumList = albumList;
    this.elasticSearchDataViewService = elasticSearchDataViewService;
    this.authorizationManager = authorizationManager;
  }

  private static ReceivePack createFor(final Repository db, final String user, String email) {
    final ReceivePack rp = new ReceivePack(db);
    rp.setRefLogIdent(new PersonIdent(user, email));
    return rp;
  }

  @Override
  public Repository open(final HttpServletRequest req, final String name)
      throws RepositoryNotFoundException, ServiceNotAuthorizedException, ServiceNotEnabledException,
          ServiceMayNotContinueException {
    final String method = req.getMethod();

    log.info("Repository: " + name);
    log.info("method: " + method);
    log.info("Auth Type: " + req.getAuthType());
    log.info("User: " + req.getUserPrincipal());
    final Enumeration<String> headerNames = req.getHeaderNames();
    while (headerNames.hasMoreElements()) {
      final String headerName = headerNames.nextElement();
      log.info(headerName + ": " + req.getHeader(headerName));
    }

    final String authorization = req.getHeader("Authorization");
    if (authorization == null) {
      throw new ServiceNotAuthorizedException("Missing auth header");
    }
    final StringTokenizer st = new StringTokenizer(authorization);
    if (!st.hasMoreTokens()) {
      throw new ServiceNotAuthorizedException("Empty auth header");
    }
    final String basic = st.nextToken();

    if (!basic.equalsIgnoreCase("Basic")) {
      throw new ServiceNotAuthorizedException("Unsupported Method: " + basic);
    }
    final String credentials = new String(Base64.getDecoder().decode(st.nextToken()));
    final int splitPosition = credentials.indexOf(":");
    if (splitPosition == -1) {
      throw new ServiceNotAuthorizedException("Invalid authentication token: " + basic);
    }
    final String username = credentials.substring(0, splitPosition).trim();
    final String password = credentials.substring(splitPosition + 1).trim();

    final UUID albumId = UUID.fromString(name);
    final Repository repository =
        elasticSearchDataViewService
            .findAndValidateTemporaryPassword(UUID.fromString(username), password)
            .flatMap(user -> albumList.getAlbum(albumId).flatMap(GitAccess::getRepository))
            .block(Duration.ofSeconds(10));
    if (repository == null) {
      throw new ServiceNotAuthorizedException("Invalid user / password");
    }
    reverseMap.put(repository, albumId);
    return repository;
  }

  @Override
  public ReceivePack create(HttpServletRequest req, Repository db)
      throws ServiceNotEnabledException, ServiceNotAuthorizedException {
    final UUID albumId = reverseMap.get(db);

    final User user =
        elasticSearchDataViewService
            .findUserById(UUID.fromString(req.getRemoteUser()))
            .filterWhen(u -> authorizationManager.canUserModifyAlbum(Mono.just(u), albumId))
            .block(Duration.ofSeconds(10));

    if (user != null)
      return createFor(db, user.getUserData().getName(), user.getUserData().getEmail());
    throw new ServiceNotAuthorizedException();
  }
}
