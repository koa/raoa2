package ch.bergturbenthal.raoa.libs;

import ch.bergturbenthal.raoa.libs.properties.Properties;
import ch.bergturbenthal.raoa.libs.repository.AlbumDataRepository;
import ch.bergturbenthal.raoa.libs.repository.SyncAlbumDataEntryRepository;
import ch.bergturbenthal.raoa.libs.service.AsyncService;
import ch.bergturbenthal.raoa.libs.service.impl.BareGitAccess;
import ch.bergturbenthal.raoa.libs.service.impl.ExecutorAsyncService;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.*;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.elasticsearch.ReactiveRestClientProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.data.elasticsearch.repository.config.EnableReactiveElasticsearchRepositories;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

@Configuration
@EnableConfigurationProperties(Properties.class)
@ComponentScan(basePackageClasses = BareGitAccess.class)
@EnableReactiveElasticsearchRepositories(basePackageClasses = AlbumDataRepository.class)
@EnableElasticsearchRepositories(basePackageClasses = SyncAlbumDataEntryRepository.class)
@Import(PatchedElasticsearchConfigurationSupport.class)
@Slf4j
public class RaoaLibConfiguration {
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

  @Bean
  public AsyncService asyncService() {
    final CustomizableThreadFactory factory = new CustomizableThreadFactory("async-service");
    factory.setDaemon(true);
    final ThreadPoolExecutor executor =
        new ThreadPoolExecutor(0, 50, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), factory);

    return new ExecutorAsyncService(executor);
  }

  @Bean
  @ConditionalOnMissingBean
  ScheduledExecutorService executorService() {
    return Executors.newScheduledThreadPool(10);
  }
}
