package ch.bergturbenthal.raoa.viewer;

import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.autoconfigure.security.oauth2.client.EnableOAuth2Sso;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

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
    http.cors().disable();
    http.headers().cacheControl().disable();
    final AuthenticationFailureHandler failureHandler =
        new AuthenticationFailureHandler() {
          @Override
          public void onAuthenticationFailure(
              final HttpServletRequest request,
              final HttpServletResponse response,
              final AuthenticationException exception)
              throws IOException, ServletException {
            response.setStatus(401);
          }
        };
    if (properties.isEnableAuthentication())
      http.authorizeRequests()
          .antMatchers("/manifest.webmanifest", "/assets/icons/*.png", "/*.js")
          .permitAll()
          .requestMatchers(EndpointRequest.toAnyEndpoint())
          .permitAll()
          .and()
          .oauth2Login()
          .and()
          .oauth2Client();
  }
}
