package ch.bergturbenthal.raoa.viewer;

import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.autoconfigure.security.oauth2.client.EnableOAuth2Sso;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

@Configuration
@EnableWebSecurity
@EnableOAuth2Sso
@Order(80)
public class SecurityConfiguration extends WebSecurityConfigurerAdapter {
  private final ViewerProperties properties;

  public SecurityConfiguration(final ViewerProperties properties) {
    this.properties = properties;
  }

  @Override
  protected void configure(HttpSecurity http) throws Exception {
    http.csrf().disable();
    http.headers().cacheControl().disable();
    if (properties.isEnableAuthentication())
      http.authorizeRequests()
          .antMatchers("/manifest.webmanifest", "/assets/icons/*.png", "/*.js")
          .permitAll()
          .requestMatchers(EndpointRequest.toAnyEndpoint())
          .permitAll()
          .anyRequest()
          .authenticated()
          .and()
          .oauth2Login()
          .and()
          .oauth2Client();
  }
}
