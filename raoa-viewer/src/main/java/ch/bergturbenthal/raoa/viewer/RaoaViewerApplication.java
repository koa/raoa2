package ch.bergturbenthal.raoa.viewer;

import ch.bergturbenthal.raoa.libs.PatchedElasticsearchConfigurationSupport;
import ch.bergturbenthal.raoa.libs.RaoaLibConfiguration;
import ch.bergturbenthal.raoa.viewer.interfaces.AlbumListController;
import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.impl.RemoteThumbnailManager;
import graphql.schema.*;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.elasticsearch.ElasticSearchRestHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@SpringBootApplication(exclude = {ElasticSearchRestHealthContributorAutoConfiguration.class})
@EnableConfigurationProperties(ViewerProperties.class)
@Import({
  RaoaLibConfiguration.class,
  ResourceServerConfig.class,
  PatchedElasticsearchConfigurationSupport.class
})
@ComponentScan(basePackageClasses = {RemoteThumbnailManager.class, AlbumListController.class})
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
                return Instant.parse((CharSequence) input);
              }

              @Override
              public Instant parseLiteral(final Object input) throws CoercingParseLiteralException {
                return Instant.parse((CharSequence) input);
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

}
