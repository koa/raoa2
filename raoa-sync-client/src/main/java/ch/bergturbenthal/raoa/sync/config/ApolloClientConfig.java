package ch.bergturbenthal.raoa.sync.config;

import com.apollographql.apollo.ApolloClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ApolloClientConfig {

  @Bean
  public ApolloClient universeApolloClient(
      final SyncProperties appProperties, final DateGraphQLAdapter dateGraphQLAdapter) {
    return ApolloClient.builder()
        .okHttpClient(getOkHttpClient(appProperties))
        // .addCustomTypeAdapter(CustomType.DATE, dateGraphQLAdapter)
        .serverUrl(appProperties.getUri().resolve("/graphql").toString())
        .build();
  }

  private OkHttpClient getOkHttpClient(final SyncProperties service) {
    final HttpLoggingInterceptor httpLoggingInterceptor = new HttpLoggingInterceptor();
    httpLoggingInterceptor.setLevel(HttpLoggingInterceptor.Level.HEADERS);
    // httpLoggingInterceptor.redactHeader("apiKey");

    return new OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .addInterceptor(httpLoggingInterceptor)
        .addInterceptor(addAuthenticationHeader(service))
        .build();
  }

  private Interceptor addAuthenticationHeader(final SyncProperties service) {
    final String authorization =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    (service.getUsername() + ":" + service.getPassword())
                        .getBytes(StandardCharsets.UTF_8));
    return chain ->
        chain.proceed(
            chain
                .request()
                .newBuilder()
                // .addHeader(HttpHeaders.USER_AGENT, service.getName())
                .addHeader("Authorization", authorization)
                .addHeader("Connection", "close")
                .build());
  }
}
