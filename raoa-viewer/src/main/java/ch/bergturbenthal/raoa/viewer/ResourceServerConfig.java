package ch.bergturbenthal.raoa.viewer;

import ch.bergturbenthal.raoa.elastic.service.impl.ElasticSearchDataViewService;
import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import jakarta.servlet.http.Cookie;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Configuration
@EnableWebSecurity
public class ResourceServerConfig {

    private final ViewerProperties oAuthProperties;
    private final ElasticSearchDataViewService elasticSearchDataViewService;

    public ResourceServerConfig(final ViewerProperties oAuthProperties,
            final ElasticSearchDataViewService elasticSearchDataViewService) {
        this.oAuthProperties = oAuthProperties;
        this.elasticSearchDataViewService = elasticSearchDataViewService;
    }

    @Bean
    SecurityFilterChain webOAuth2FilterChain(final HttpSecurity httpSecurity) throws Exception {
        final DefaultBearerTokenResolver defaultBearerTokenResolver = new DefaultBearerTokenResolver();
        return httpSecurity.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(
                        resourceServer -> resourceServer.jwt(Customizer.withDefaults()).bearerTokenResolver(request -> {
                            // log.info("Stacktrace", new Throwable());
                            final Optional<String> s = Optional.ofNullable(request.getCookies())
                                    .flatMap(cookies -> Arrays.stream(cookies)
                                            .filter(cookie -> "access_token".equals(cookie.getName()))
                                            .map(Cookie::getValue).findAny());
                            final String token = s.orElseGet(() -> defaultBearerTokenResolver.resolve(request));
                            if (token == null)
                                return null;
                            try {
                                final JWT jwt = JWTParser.parse(token);
                                final JWTClaimsSet jwtClaimsSet = jwt.getJWTClaimsSet();
                                final Date expirationTime = jwtClaimsSet.getExpirationTime();
                                if (expirationTime.toInstant().isBefore(Instant.now()))
                                    return null;
                            } catch (ParseException e) {
                                log.warn("Invalid token", e);
                                return null;
                            }
                            return token;
                        }))
                .addFilter(new BasicAuthenticationFilter(authentication -> {
                    if (authentication instanceof UsernamePasswordAuthenticationToken) {
                        String username = authentication.getName();
                        String password = (String) authentication.getCredentials();

                        return elasticSearchDataViewService
                                .findAndValidateTemporaryPassword(UUID.fromString(username), password)
                                .<Authentication> map(user -> {
                                    final Collection<? extends GrantedAuthority> authorities = Collections
                                            .singletonList(new SimpleGrantedAuthority(
                                                    user.isSuperuser() ? "SUPERUSER" : "USER"));
                                    UsernamePasswordAuthenticationToken result = new UsernamePasswordAuthenticationToken(
                                            authentication.getPrincipal(), authentication.getCredentials(),
                                            authorities);
                                    result.setDetails(user);
                                    return result;
                                }).defaultIfEmpty(authentication).block(Duration.ofSeconds(10));
                    }

                    return authentication;
                })).build();
    }

    /*
     * @Bean public TokenStore tokenStore(ResourceServerProperties resource) { final DefaultAccessTokenConverter
     * acccessTokenConverter = new DefaultAccessTokenConverter(); final UserAuthenticationConverter us = new
     * UserAuthenticationConverter() {
     * @Override public Map<String, ?> convertUserAuthentication(final Authentication userAuthentication) { return null;
     * }
     * @Override public Authentication extractAuthentication(final Map<String, ?> map) { final SimpleGrantedAuthority
     * oidcUserAuthority = new SimpleGrantedAuthority("x"); final List<GrantedAuthority> authorities =
     * Collections.singletonList(oidcUserAuthority); final OAuth2User principal = new DefaultOAuth2User(authorities,
     * (Map<String, Object>) map, "name"); final String id = map.get("sub").toString(); final OAuth2AuthenticationToken
     * oAuth2AuthenticationToken = new OAuth2AuthenticationToken(principal, authorities, "google"); return
     * oAuth2AuthenticationToken; } }; acccessTokenConverter.setUserTokenConverter(us); return new
     * JwkTokenStore(resource.getJwk().getKeySetUri(), acccessTokenConverter); }
     * @Override public void configure(ResourceServerSecurityConfigurer resources) throws Exception {
     * resources.resourceId(oAuthProperties.getClientProperties().getGoogleClientId()); }
     */

    @Bean
    // @ConditionalOnProperty(prefix = "raoa.viewer", name = "allowAlsoDebugging", havingValue =
    // "true")
    CorsConfigurationSource corsConfigurationSource() {
        log.info("Allow Access from localhost:4200");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.addAllowedOrigin("http://localhost:4200");
        corsConfiguration.setAllowedMethods(Arrays.asList(HttpMethod.GET.name(), HttpMethod.HEAD.name(),
                HttpMethod.POST.name(), HttpMethod.PUT.name(), HttpMethod.DELETE.name()));
        corsConfiguration.setMaxAge(1800L);
        source.registerCorsConfiguration("/**", corsConfiguration); // you restrict your path here
        return source;
    }

    /*
     * @Bean public GraphQlHttpHandler overriddenGraphQlHttpHandler(WebGraphQlHandler webGraphQlHandler) { return new
     * GraphQlHttpHandler(webGraphQlHandler); }
     * @Bean public RouterFunction<ServerResponse> overriddenGraphQlRouterFunction( GraphQlHttpHandler handler) { String
     * graphQLPath = "/graphql"; if (log.isInfoEnabled()) { log.info("GraphQL endpoint HTTP POST " + graphQLPath); }
     * RouterFunctions.Builder builder = RouterFunctions.route() .GET( graphQLPath, request ->
     * ServerResponse.status(HttpStatus.METHOD_NOT_ALLOWED) .headers( headers ->
     * headers.setAllow(Collections.singleton(HttpMethod.POST))) .build()) .POST( graphQLPath,
     * contentType(MediaType.APPLICATION_JSON).and(accept(MediaType.APPLICATION_JSON)), handler::handleRequest); return
     * builder.build(); }
     */
}
