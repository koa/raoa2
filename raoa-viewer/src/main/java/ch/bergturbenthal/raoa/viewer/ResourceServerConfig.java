package ch.bergturbenthal.raoa.viewer;

import ch.bergturbenthal.raoa.elastic.service.impl.ElasticSearchDataViewService;
import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.servlet.http.Cookie;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.security.oauth2.resource.ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableResourceServer;
import org.springframework.security.oauth2.config.annotation.web.configuration.ResourceServerConfigurerAdapter;
import org.springframework.security.oauth2.config.annotation.web.configurers.ResourceServerSecurityConfigurer;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.provider.token.DefaultAccessTokenConverter;
import org.springframework.security.oauth2.provider.token.TokenStore;
import org.springframework.security.oauth2.provider.token.UserAuthenticationConverter;
import org.springframework.security.oauth2.provider.token.store.jwk.JwkTokenStore;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

@Slf4j
@Configuration
@EnableResourceServer
@EnableWebSecurity
public class ResourceServerConfig extends ResourceServerConfigurerAdapter {

  private final ViewerProperties oAuthProperties;
  private final ElasticSearchDataViewService elasticSearchDataViewService;

  public ResourceServerConfig(
      final ViewerProperties oAuthProperties,
      final ElasticSearchDataViewService elasticSearchDataViewService) {
    this.oAuthProperties = oAuthProperties;
    this.elasticSearchDataViewService = elasticSearchDataViewService;
  }

  @Override
  public void configure(final HttpSecurity http) throws Exception {
    http.sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS);
    http.authorizeRequests().anyRequest().permitAll();
    http.headers().cacheControl().disable();
    final DefaultBearerTokenResolver defaultBearerTokenResolver = new DefaultBearerTokenResolver();
    http.cors()
        .and()
        .oauth2ResourceServer()
        .bearerTokenResolver(
            request -> {
              // log.info("Stacktrace", new Throwable());
              final Optional<String> s =
                  Optional.ofNullable(request.getCookies())
                      .flatMap(
                          cookies ->
                              Arrays.stream(cookies)
                                  .filter(cookie -> "access_token".equals(cookie.getName()))
                                  .map(Cookie::getValue)
                                  .findAny());
              final String token = s.orElseGet(() -> defaultBearerTokenResolver.resolve(request));
              if (token == null) return null;
              try {
                final JWT jwt = JWTParser.parse(token);
                final JWTClaimsSet jwtClaimsSet = jwt.getJWTClaimsSet();
                final Date expirationTime = jwtClaimsSet.getExpirationTime();
                if (expirationTime.toInstant().isBefore(Instant.now())) return null;
              } catch (ParseException e) {
                log.warn("Invalid token", e);
                return null;
              }
              return token;
            })
        .jwt()
        .and()
        .and()
        .addFilter(
            new BasicAuthenticationFilter(
                authentication -> {
                  if (authentication instanceof UsernamePasswordAuthenticationToken) {
                    String username = authentication.getName();
                    String password = (String) authentication.getCredentials();

                    return elasticSearchDataViewService
                        .findAndValidateTemporaryPassword(UUID.fromString(username), password)
                        .<Authentication>map(
                            user -> {
                              final Collection<? extends GrantedAuthority> authorities =
                                  Collections.singletonList(
                                      new SimpleGrantedAuthority(
                                          user.isSuperuser() ? "SUPERUSER" : "USER"));
                              UsernamePasswordAuthenticationToken result =
                                  new UsernamePasswordAuthenticationToken(
                                      authentication.getPrincipal(),
                                      authentication.getCredentials(),
                                      authorities);
                              result.setDetails(user);
                              return result;
                            })
                        .defaultIfEmpty(authentication)
                        .block(Duration.ofSeconds(10));
                  }

                  return authentication;
                }))
    /*.addFilterAt(new BasicAuthenticationFilter(authentication -> {
      return authentication;
    }), SecurityContextPersistenceFilter.class)*/
    ;
  }

  @Bean
  public TokenStore tokenStore(ResourceServerProperties resource) {
    final DefaultAccessTokenConverter acccessTokenConverter = new DefaultAccessTokenConverter();
    final UserAuthenticationConverter us =
        new UserAuthenticationConverter() {
          @Override
          public Map<String, ?> convertUserAuthentication(final Authentication userAuthentication) {
            return null;
          }

          @Override
          public Authentication extractAuthentication(final Map<String, ?> map) {
            final SimpleGrantedAuthority oidcUserAuthority = new SimpleGrantedAuthority("x");
            final List<GrantedAuthority> authorities = Collections.singletonList(oidcUserAuthority);
            final OAuth2User principal =
                new DefaultOAuth2User(authorities, (Map<String, Object>) map, "name");
            final String id = map.get("sub").toString();
            final OAuth2AuthenticationToken oAuth2AuthenticationToken =
                new OAuth2AuthenticationToken(principal, authorities, "google");
            return oAuth2AuthenticationToken;
          }
        };
    acccessTokenConverter.setUserTokenConverter(us);
    return new JwkTokenStore(resource.getJwk().getKeySetUri(), acccessTokenConverter);
  }

  @Override
  public void configure(ResourceServerSecurityConfigurer resources) throws Exception {
    resources.resourceId(oAuthProperties.getClientProperties().getGoogleClientId());
  }
}
