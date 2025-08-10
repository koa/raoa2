package ch.bergturbenthal.raoa.viewer;

import ch.bergturbenthal.raoa.elastic.RaoaElasticConfiguration;
import ch.bergturbenthal.raoa.viewer.interfaces.AlbumListController;
import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.impl.DefaultAuthorizationManager;
import graphql.scalars.ExtendedScalars;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collection;
import java.util.Enumeration;

@Slf4j
@SpringBootApplication()
@EnableConfigurationProperties(ViewerProperties.class)
@Import({ RaoaElasticConfiguration.class, ResourceServerConfig.class })
@ComponentScan(basePackageClasses = { DefaultAuthorizationManager.class, AlbumListController.class })
@EnableScheduling
public class RaoaViewerApplication {

    public static void main(String[] args) {

        final ConfigurableApplicationContext run = SpringApplication.run(RaoaViewerApplication.class, args);
    }

    @Bean
    public ServletRegistrationBean<GitServlet> gitServlet(final RepositoryResolver<HttpServletRequest> resolver,
            final ReceivePackFactory<HttpServletRequest> receivePackFactory) {
        final GitServlet servlet = new GitServlet();

        servlet.setRepositoryResolver(resolver);
        servlet.setReceivePackFactory(receivePackFactory);
        return new ServletRegistrationBean<>(servlet, "/git/*");
    }

    @Bean
    public OncePerRequestFilter appendHeaderFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(final @NotNull HttpServletRequest request,
                    final @NotNull HttpServletResponse response, final @NotNull FilterChain filterChain)
                    throws ServletException, IOException {
                final String servletPath = request.getServletPath();
                if (servletPath.equals("/git"))
                    response.setHeader("WWW-Authenticate", "Basic realm=Git");
                filterChain.doFilter(request, response);
            }
        };
    }

    // @Bean
    public OncePerRequestFilter requestLoggingFilter() {

        return new OncePerRequestFilter() {

            @Override
            protected void doFilterInternal(final @NotNull HttpServletRequest request,
                    final @NotNull HttpServletResponse response, final @NotNull FilterChain filterChain)
                    throws ServletException, IOException {
                final String requestURI = request.getRequestURI();
                filterChain.doFilter(request, response);
                final Collection<String> responseHeaders = response.getHeaderNames();
                final Enumeration<String> requestHeaders = request.getHeaderNames();
                log.info("Request: " + requestURI + " code: " + response.getStatus());
                while (requestHeaders.hasMoreElements()) {
                    String headerName = requestHeaders.nextElement();
                    log.info(" - " + headerName + ": " + String.join(", ", request.getHeader(headerName)));
                }
                log.info("-------------");
                for (String headerName : responseHeaders) {
                    log.info(" - " + headerName + ": " + String.join(", ", response.getHeaders(headerName)));
                }
            }
        };
    }

    @Bean
    public RuntimeWiringConfigurer scalarConfigurer() {
        return builder -> builder.scalar(ExtendedScalars.DateTime).scalar(ExtendedScalars.GraphQLLong);
    }
}
