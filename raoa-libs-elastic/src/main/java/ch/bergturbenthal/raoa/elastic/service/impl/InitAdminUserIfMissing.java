package ch.bergturbenthal.raoa.elastic.service.impl;

import ch.bergturbenthal.raoa.elastic.model.AuthenticationId;
import ch.bergturbenthal.raoa.elastic.model.User;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.elastic.service.UserManager;
import ch.bergturbenthal.raoa.libs.properties.Properties;
import ch.bergturbenthal.raoa.libs.service.Updater;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
public class InitAdminUserIfMissing {

  private final Properties viewerProperties;
  private final DataViewService dataViewService;
  private final UserManager userManager;

  public InitAdminUserIfMissing(
      final Properties viewerProperties,
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
      final Mono<List<User>> existingSuperusers =
          dataViewService.findUserForAuthentication(superUserId).collectList();
      final Updater.CommitContext context =
          Updater.CommitContext.builder().message("set superuser by config").build();
      final Optional<User> createdUser =
          existingSuperusers
              .onErrorReturn(Collections.emptyList())
              .flatMap(
                  maybeUser -> {
                    if (maybeUser.size() > 0) {
                      final User existingUser = maybeUser.get(0);
                      final Mono<User> modifiedUser;
                      if (!existingUser.isSuperuser()) {
                        modifiedUser =
                            userManager.context(
                                existingUser.getId(),
                                user -> user.toBuilder().superuser(true).build(),
                                context);
                      } else modifiedUser = Mono.empty();
                      if (maybeUser.size() > 1) {
                        final List<User> additionalUsers = maybeUser.subList(1, maybeUser.size());
                        return Flux.fromIterable(additionalUsers)
                            .map(User::getId)
                            .flatMap(
                                id ->
                                    userManager
                                        .removeUser(id, context)
                                        .doOnNext(
                                            (done) ->
                                                log.info(
                                                    "Removed duplicate user " + id + ", " + done)),
                                1)
                            .then(modifiedUser);
                      }
                      return modifiedUser;
                    } else {
                      return dataViewService
                          .getPendingRequest(superUserId)
                          .flatMap(baseRequest -> userManager.createNewUser(baseRequest, context));
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
