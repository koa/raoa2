package ch.bergturbenthal.raoa.viewer.service.impl;

import ch.bergturbenthal.raoa.libs.repository.UserRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class ElasticSearchHealthIndicator implements HealthIndicator {
  private final UserRepository userRepository;

  public ElasticSearchHealthIndicator(final UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @Override
  public Health health() {
    return userRepository
        .count()
        .map(c -> Health.up().withDetail("usersCount", c).build())
        .onErrorResume(ex -> Mono.just(Health.down((Exception) ex).build()))
        .block();
  }
}
