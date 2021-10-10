package ch.bergturbenthal.raoa.viewer;

import ch.bergturbenthal.raoa.elastic.RaoaElasticConfiguration;
import ch.bergturbenthal.raoa.viewer.interfaces.AlbumListController;
import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.impl.DefaultAuthorizationManager;
import graphql.scalars.ExtendedScalars;
import graphql.schema.*;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Enumeration;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.elasticsearch.ElasticSearchRestHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@SpringBootApplication(exclude = {ElasticSearchRestHealthContributorAutoConfiguration.class})
@EnableConfigurationProperties(ViewerProperties.class)
@Import({RaoaElasticConfiguration.class, ResourceServerConfig.class})
@ComponentScan(basePackageClasses = {DefaultAuthorizationManager.class, AlbumListController.class})
@EnableScheduling
public class RaoaViewerApplication {

  public static void main(String[] args) {

    final ConfigurableApplicationContext run =
        SpringApplication.run(RaoaViewerApplication.class, args);

    /*
    final AlbumDataRepository dataRepository = run.getBean(AlbumDataRepository.class);


    final AlbumData data = AlbumData.builder()
                                    .repositoryId(UUID.randomUUID())
                                    .currentVersion(ObjectId.zeroId())
                                    .build();
    final AlbumData block = dataRepository.save(data).block();
    log.info("Stored: " + block);
    log.info("Found Entries: " + dataRepository.findAll().map(AlbumData::getRepositoryId).collectList().block());

    final AlbumDataEntryRepository albumDataEntryRepository = run.getBean(AlbumDataEntryRepository.class);
    albumDataEntryRepository.save(AlbumEntryData.builder()
                                                .albumId(UUID.randomUUID())
                                                .entryId(ObjectId.zeroId())
                                                .build()).block();
    log.info("Found Entries: " + albumDataEntryRepository.findAll()
                                                         .map(AlbumEntryData::getAlbumId)
                                                         .collectList()
                                                         .block());



     */

    /*
    try (final ConfigurableApplicationContext applicationContext =
        SpringApplication.run(RaoaViewerApplication.class, args)) {
      final AlbumList albumList = applicationContext.getBean(AlbumList.class);
      final ThumbnailManager thumbnailManager = applicationContext.getBean(ThumbnailManager.class);

      Instant startTime = Instant.now();
      final Long fileCount =
          Flux.fromStream(albumList.listAlbums())
              .map(AlbumList.FoundAlbum::getAccess)
              .flatMap(
                  gitAccess -> {
                    try {
                      return Flux.fromIterable(gitAccess.listFiles(PathSuffixFilter.create(".JPG")))
                          .map(e -> Tuples.of(gitAccess, e));
                    } catch (IOException ex) {
                      return Flux.error(ex);
                    }
                  })
              .flatMap(
                  e -> {
                    try {
                      final GitAccess gitAccess = e.getT1();
                      final GitAccess.GitFileEntry fileEntry = e.getT2();
                      final Optional<ObjectLoader> optionalObjectLoader =
                          gitAccess.readObject(fileEntry.getFileId());
                      if (optionalObjectLoader.isPresent()) {
                        return thumbnailManager
                            .takeThumbnail(
                                fileEntry.getFileId(),
                                optionalObjectLoader.get(),
                                MediaType.IMAGE_JPEG)
                            .map(f -> Tuples.of(fileEntry.getNameString(), f))
                            .onErrorResume(
                                ex -> {
                                  log.warn("Cannot convert file " + fileEntry.getNameString(), ex);
                                  return Mono.empty();
                                });
                      }
                      return Mono.empty();
                    } catch (IOException ex) {
                      return Mono.error(ex);
                    }
                  })
              .doOnNext(
                  e -> {
                    e.getT2().renameTo(new File("/tmp", e.getT1()));
                    log.info("Translated " + e.getT1());
                  })
              .count()
              .block();
      Instant endTime = Instant.now();
      final Duration executeDuration = Duration.between(startTime, endTime);
      log.info("Processed " + fileCount + " files");
      log.info("Used " + executeDuration.getSeconds() + "s");
      double filesPerSecond = fileCount * 1000.0 / executeDuration.toMillis();
      log.info("Speed: " + filesPerSecond + " Files/s");
    }
    */
  }

  @Bean
  public GraphQLScalarType graphQlDateTimeScalar() {
    return GraphQLScalarType.newScalar()
        .name("DateTime")
        .coercing(
            new Coercing<Instant, String>() {
              @Override
              public String serialize(final Object dataFetcherResult)
                  throws CoercingSerializeException {
                return ((Instant) dataFetcherResult).toString();
              }

              @Override
              public Instant parseValue(final Object input) throws CoercingParseValueException {
                return DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(
                    (CharSequence) input, Instant::from);
              }

              @Override
              public Instant parseLiteral(final Object input) throws CoercingParseLiteralException {
                return DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(
                    (CharSequence) input, Instant::from);
              }
            })
        .build();
  }

  /*
    @Bean
    public EntityMapper entityMapper(SimpleElasticsearchMappingContext mappingContext) {
      return new CustomEntityMapper(mappingContext);
    }
  */

  @Bean
  public ServletRegistrationBean<GitServlet> gitServlet(
      final RepositoryResolver<HttpServletRequest> resolver,
      final ReceivePackFactory<HttpServletRequest> receivePackFactory) {
    final GitServlet servlet = new GitServlet();

    servlet.setRepositoryResolver(resolver);
    servlet.setReceivePackFactory(receivePackFactory);
    return new ServletRegistrationBean<>(servlet, "/git/*");
  }

  @Bean
  public OncePerRequestFilter appendHeaderFilter() {
    return new OncePerRequestFilter() {
      @Override
      protected void doFilterInternal(
          final @NotNull HttpServletRequest request,
          final @NotNull HttpServletResponse response,
          final @NotNull FilterChain filterChain)
          throws ServletException, IOException {
        final String servletPath = request.getServletPath();
        if (servletPath.equals("/git")) response.setHeader("WWW-Authenticate", "Basic realm=Git");
        filterChain.doFilter(request, response);
      }
    };
  }

  // @Bean
  public OncePerRequestFilter requestLoggingFilter() {

    return new OncePerRequestFilter() {

      @Override
      protected void doFilterInternal(
          final @NotNull HttpServletRequest request,
          final @NotNull HttpServletResponse response,
          final @NotNull FilterChain filterChain)
          throws ServletException, IOException {
        final String requestURI = request.getRequestURI();
        filterChain.doFilter(request, response);
        final Collection<String> responseHeaders = response.getHeaderNames();
        final Enumeration<String> requestHeaders = request.getHeaderNames();
        log.info("Request: " + requestURI + " code: " + response.getStatus());
        while (requestHeaders.hasMoreElements()) {
          String headerName = requestHeaders.nextElement();
          log.info(" - " + headerName + ": " + String.join(", ", request.getHeader(headerName)));
        }
        log.info("-------------");
        for (String headerName : responseHeaders) {
          log.info(" - " + headerName + ": " + String.join(", ", response.getHeaders(headerName)));
        }
      }
    };
  }

  @Bean
  public RuntimeWiringConfigurer scalarConfigurer() {
    return builder -> builder.scalar(ExtendedScalars.DateTime);
  }
}
