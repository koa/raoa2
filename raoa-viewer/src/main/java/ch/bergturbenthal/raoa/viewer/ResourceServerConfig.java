package ch.bergturbenthal.raoa.viewer;

import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.oauth2.resource.ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

@Configuration
@EnableResourceServer
@EnableWebSecurity
public class ResourceServerConfig extends ResourceServerConfigurerAdapter {

  @Autowired private ViewerProperties oAuthProperties;

  @Override
  public void configure(final HttpSecurity http) throws Exception {
    http.sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS);
    http.authorizeRequests().anyRequest().permitAll();
    http.headers().cacheControl().disable();
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
