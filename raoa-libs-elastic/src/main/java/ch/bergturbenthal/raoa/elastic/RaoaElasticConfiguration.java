package ch.bergturbenthal.raoa.elastic;

import ch.bergturbenthal.raoa.elastic.repository.AlbumDataRepository;
import ch.bergturbenthal.raoa.elastic.repository.SyncAlbumDataEntryRepository;
import ch.bergturbenthal.raoa.elastic.service.impl.ElasticSearchDataViewService;
import ch.bergturbenthal.raoa.libs.RaoaLibConfiguration;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.boot.autoconfigure.data.elasticsearch.ReactiveElasticsearchRestClientProperties;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.context.annotation.*;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.Jsr310Converters;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.RestClients;
import org.springframework.data.elasticsearch.client.reactive.ReactiveElasticsearchClient;
import org.springframework.data.elasticsearch.client.reactive.ReactiveRestClients;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.ReactiveElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.convert.ElasticsearchConverter;
import org.springframework.data.elasticsearch.core.convert.ElasticsearchCustomConversions;
import org.springframework.data.elasticsearch.core.convert.MappingElasticsearchConverter;
import org.springframework.data.elasticsearch.core.mapping.SimpleElasticsearchMappingContext;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.data.elasticsearch.repository.config.EnableReactiveElasticsearchRepositories;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.ExchangeStrategies;

@Slf4j
@Configuration
@EnableReactiveElasticsearchRepositories(basePackageClasses = AlbumDataRepository.class)
@EnableElasticsearchRepositories(basePackageClasses = SyncAlbumDataEntryRepository.class)
@Import({RaoaLibConfiguration.class})
@ComponentScan(basePackageClasses = ElasticSearchDataViewService.class)
@EnableConfigurationProperties({
  ElasticsearchProperties.class,
  ReactiveElasticsearchRestClientProperties.class
})
public class RaoaElasticConfiguration {

  static {
    log.info("Init Class");
    try {
      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, new TrustManager[] {new DummyX509TrustManager()}, null);
      SSLContext.setDefault(sslContext);
    } catch (NoSuchAlgorithmException | KeyManagementException e) {
      log.error("Cannot override TLS settings");
      e.printStackTrace();
    }
  }

  public RaoaElasticConfiguration() {
    log.info("Init Instance");
  }

  @Bean
  public ElasticsearchCustomConversions elasticsearchCustomConversions() {
    Jsr310Converters.getConvertersToRegister();
    return new ElasticsearchCustomConversions(
        Stream.concat(
                Stream.of(
                    new ObjectIdToString(),
                    new StringToObjectId(),
                    new InstantLongReader(),
                    new InstantIntegerReader(),
                    new InstantDoubleReader()),
                Jsr310Converters.getConvertersToRegister().stream())
            .collect(Collectors.toList()));
  }

  @Bean(name = {"elasticsearchOperations", "elasticsearchTemplate"})
  public ElasticsearchOperations elasticsearchOperations(
      ElasticsearchConverter elasticsearchConverter, final RestHighLevelClient client) {
    return new ElasticsearchRestTemplate(client, elasticsearchConverter);
  }

  @Bean
  ElasticsearchConverter elasticsearchConverter(
      SimpleElasticsearchMappingContext mappingContext,
      ElasticsearchCustomConversions customConversions) {
    final MappingElasticsearchConverter mappingElasticsearchConverter =
        new MappingElasticsearchConverter(mappingContext);
    mappingElasticsearchConverter.setConversions(customConversions);

    return mappingElasticsearchConverter;
  }

  @Bean
  @Primary
  ReactiveElasticsearchTemplate reactiveElasticsearchTemplate(
      ReactiveElasticsearchClient client, ElasticsearchConverter converter) {
    ReactiveElasticsearchTemplate template = new ReactiveElasticsearchTemplate(client, converter);

    template.setIndicesOptions(IndicesOptions.strictExpandOpenAndForbidClosed());
    template.setRefreshPolicy(RefreshPolicy.IMMEDIATE);
    return template;
  }

  @Bean
  public ClientConfiguration clientConfiguration(
      ElasticsearchProperties properties,
      ReactiveElasticsearchRestClientProperties restClientProperties) {
    final String[] hostAndPorts = properties.getUris().toArray(new String[0]);
    ClientConfiguration.MaybeSecureClientConfigurationBuilder builder =
        ClientConfiguration.builder().connectedTo(hostAndPorts);

    try {
      builder.usingSsl(SSLContext.getDefault());
    } catch (NoSuchAlgorithmException e) {
      log.warn("Cannot init SSL", e);
    }
    PropertyMapper map = PropertyMapper.get().alwaysApplyingWhenNonNull();

    builder.withBasicAuth(properties.getUsername(), properties.getPassword());

    map.from(properties.getConnectionTimeout()).to(builder::withConnectTimeout);
    map.from(properties.getSocketTimeout()).to(builder::withSocketTimeout);
    map.from(properties.getPathPrefix()).to(builder::withPathPrefix);

    map.from(restClientProperties.getMaxInMemorySize())
        .asInt(DataSize::toBytes)
        .to(
            (maxInMemorySize) -> {
              builder.withClientConfigurer(
                  ReactiveRestClients.WebClientConfigurationCallback.from(
                      (webClient) -> {
                        ExchangeStrategies exchangeStrategies =
                            ExchangeStrategies.builder()
                                .codecs(
                                    (configurer) ->
                                        configurer.defaultCodecs().maxInMemorySize(maxInMemorySize))
                                .build();
                        return webClient.mutate().exchangeStrategies(exchangeStrategies).build();
                      }));
            });
    return builder.build();
  }

  @Bean
  public RestHighLevelClient elasticsearchClient(ClientConfiguration configuration) {

    final RestClients.ElasticsearchRestClient elasticsearchRestClient =
        RestClients.create(configuration);
    return elasticsearchRestClient.rest();
  }

  @WritingConverter
  static class ObjectIdToString implements Converter<ObjectId, String> {

    @Override
    public String convert(final ObjectId source) {
      return source.name();
    }
  }

  @ReadingConverter
  static class StringToObjectId implements Converter<String, ObjectId> {

    @Override
    public ObjectId convert(final String source) {
      return ObjectId.fromString(source);
    }
  }

  @ReadingConverter
  static class InstantLongReader implements Converter<Long, Instant> {

    @Override
    public Instant convert(final Long source) {
      return Instant.ofEpochMilli(source);
    }
  }

  @ReadingConverter
  static class InstantIntegerReader implements Converter<Integer, Instant> {

    @Override
    public Instant convert(final Integer source) {
      return Instant.ofEpochSecond(source);
    }
  }

  @ReadingConverter
  static class InstantDoubleReader implements Converter<Double, Instant> {

    @Override
    public Instant convert(final Double source) {
      return Instant.ofEpochMilli(Math.round(source * 1000.0));
    }
  }
}
