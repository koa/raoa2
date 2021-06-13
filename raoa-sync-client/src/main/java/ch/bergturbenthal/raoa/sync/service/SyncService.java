package ch.bergturbenthal.raoa.sync.service;

import ch.bergturbenthal.raoa.libs.service.AsyncService;
import ch.bergturbenthal.raoa.sync.config.SyncProperties;
import ch.bergturbenthal.raoa.sync.graphql.client.ListAlbumsQuery;
import com.apollographql.apollo.ApolloClient;
import com.apollographql.apollo.ApolloQueryCall;
import com.apollographql.apollo.api.Response;
import com.apollographql.apollo.reactor.ReactorApollo;
import java.io.File;
import java.util.Collection;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SyncService {
  private final ApolloClient apolloClient;
  private final SyncProperties properties;
  private final AsyncService asyncService;

  public SyncService(
      final ApolloClient apolloClient,
      final SyncProperties properties,
      final AsyncService asyncService) {
    this.apolloClient = apolloClient;
    this.properties = properties;
    this.asyncService = asyncService;
  }

  public void sync() {
    final ApolloQueryCall<ListAlbumsQuery.Data> query =
        apolloClient.query(ListAlbumsQuery.builder().build());
    final Long count =
        ReactorApollo.from(query)
            .map(Response::getData)
            .flatMapIterable(ListAlbumsQuery.Data::listAlbums)
            .filter(album -> album.id().equals("adbf19bb-c9e7-a6f6-754c-d02c46d968a8"))
            .doOnNext(album -> log.info("Album: " + album.albumPath()))
            .flatMap(
                album ->
                    asyncService.asyncMono(
                        () -> {
                          final File repoDir = createRepoDir(album);
                          final File parent = repoDir.getParentFile();
                          if (!parent.exists()) parent.mkdirs();
                          final String remoteUri =
                              properties.getUri().resolve("/git/" + album.id()).toString();
                          log.info("Remote: " + remoteUri);

                          if (!repoDir.exists()) {
                            Git.cloneRepository()
                                .setDirectory(repoDir)
                                .setBare(true)
                                .setURI(remoteUri)
                                .setCredentialsProvider(
                                    new UsernamePasswordCredentialsProvider(
                                        properties.getUsername(), properties.getPassword()))
                                .setCallback(
                                    new CloneCommand.Callback() {
                                      @Override
                                      public void initializedSubmodules(
                                          final Collection<String> submodules) {}

                                      @Override
                                      public void cloningSubmodule(final String path) {}

                                      @Override
                                      public void checkingOut(
                                          final AnyObjectId commit, final String path) {
                                        log.info("Checkout " + path);
                                      }
                                    })
                                .call();
                          }
                          return true;
                        }))
            .count()
            .block();
    log.info("Count: " + count);
  }

  @NotNull
  private File createRepoDir(final ListAlbumsQuery.ListAlbum album) {
    File repoDir = new File(properties.getRepository());
    for (String filename : album.albumPath()) {
      repoDir = new File(repoDir, filename);
    }
    return new File(repoDir.getParentFile(), repoDir.getName() + ".git");
  }
}
