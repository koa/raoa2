package ch.bergturbenthal.raoa.viewer.service.impl;

import ch.bergturbenthal.raoa.elastic.model.AuthenticationId;
import ch.bergturbenthal.raoa.elastic.model.User;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.elastic.service.UserManager;
import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class InitAdminUserIfMissing {

  private final ViewerProperties viewerProperties;
  private final DataViewService dataViewService;
  private final UserManager userManager;

  public InitAdminUserIfMissing(
      final ViewerProperties viewerProperties,
      final DataViewService dataViewService,
      final UserManager userManager) {
    this.viewerProperties = viewerProperties;
    this.dataViewService = dataViewService;
    this.userManager = userManager;
  }

  @Scheduled(fixedDelay = 1000 * 60)
  // TODO: Inject Admin-User per API
  public void checkAndFixAdminUser() {
    try {
      final String superuser = viewerProperties.getSuperuser();
      if (superuser == null) return;
      final AuthenticationId superUserId =
          AuthenticationId.builder().authority("accounts.google.com").id(superuser).build();
      final Mono<User> existingSuperuser = dataViewService.findUserForAuthentication(superUserId);
      final Optional<User> createdUser =
          existingSuperuser
              .map(Optional::of)
              .defaultIfEmpty(Optional.empty())
              .onErrorReturn(Optional.empty())
              .flatMap(
                  maybeUser -> {
                    if (maybeUser.isPresent()) {
                      if (!maybeUser.get().isSuperuser()) {
                        return userManager.updateUser(
                            maybeUser.get().getId(),
                            user -> user.toBuilder().superuser(true).build(),
                            "set superuser by config");
                      }
                      return Mono.empty();
                    } else {
                      return dataViewService
                          .getPendingRequest(superUserId)
                          .flatMap(userManager::createNewUser);
                    }
                  })
              .flatMap(
                  updatedUser ->
                      dataViewService.updateUserData().cast(User.class).defaultIfEmpty(updatedUser))
              .doOnNext(user -> log.info("Updated superuser" + user))
              .blockOptional(Duration.of(1, ChronoUnit.MINUTES));
      log.info("Created user: " + createdUser);
    } catch (Exception ex) {
      log.warn("Cannot check superuser", ex);
    }
  }
}
