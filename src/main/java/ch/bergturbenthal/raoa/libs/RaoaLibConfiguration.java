package ch.bergturbenthal.raoa.libs;

import ch.bergturbenthal.raoa.libs.properties.Properties;
import ch.bergturbenthal.raoa.libs.service.impl.BareGitAccess;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(Properties.class)
@ComponentScan(basePackageClasses = BareGitAccess.class)
public class RaoaLibConfiguration {}
