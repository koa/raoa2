package ch.bergturbenthal.raoa.elastic;

import ch.bergturbenthal.raoa.elastic.repository.AlbumDataRepository;
import ch.bergturbenthal.raoa.elastic.repository.SyncAlbumDataEntryRepository;
import ch.bergturbenthal.raoa.elastic.service.impl.ElasticSearchDataViewService;
import ch.bergturbenthal.raoa.libs.RaoaLibConfiguration;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.elasticsearch.ReactiveRestClientProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.data.elasticsearch.repository.config.EnableReactiveElasticsearchRepositories;
import org.springframework.http.HttpHeaders;

@Slf4j
@EnableReactiveElasticsearchRepositories(basePackageClasses = AlbumDataRepository.class)
@EnableElasticsearchRepositories(basePackageClasses = SyncAlbumDataEntryRepository.class)
@Import({RaoaLibConfiguration.class, PatchedElasticsearchConfigurationSupport.class})
@ComponentScan(basePackageClasses = ElasticSearchDataViewService.class)
public class RaoaElasticConfiguration {

  static {
    try {
      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, new TrustManager[] {new DummyX509TrustManager()}, null);
      SSLContext.setDefault(sslContext);
    } catch (NoSuchAlgorithmException | KeyManagementException e) {
      log.error("Cannot override TLS settings");
      e.printStackTrace();
    }
  }

  @Bean
  public ClientConfiguration clientConfiguration(ReactiveRestClientProperties properties)
      throws KeyManagementException, NoSuchAlgorithmException {
    ClientConfiguration.MaybeSecureClientConfigurationBuilder builder =
        ClientConfiguration.builder().connectedTo(properties.getEndpoints().toArray(new String[0]));
    if (properties.isUseSsl()) {
      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, new TrustManager[] {new DummyX509TrustManager()}, null);
      builder.usingSsl(sslContext);
    }
    configureTimeouts(builder, properties);
    return builder.build();
  }

  private void configureTimeouts(
      ClientConfiguration.TerminalClientConfigurationBuilder builder,
      ReactiveRestClientProperties properties) {
    PropertyMapper map = PropertyMapper.get();
    map.from(properties.getConnectionTimeout()).whenNonNull().to(builder::withConnectTimeout);
    map.from(properties.getSocketTimeout()).whenNonNull().to(builder::withSocketTimeout);
    map.from(properties.getUsername())
        .whenHasText()
        .to(
            (username) -> {
              HttpHeaders headers = new HttpHeaders();
              headers.setBasicAuth(username, properties.getPassword());
              builder.withDefaultHeaders(headers);
            });
  }
}