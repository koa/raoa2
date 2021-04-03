package ch.bergturbenthal.raoa.viewer.service.impl;

import ch.bergturbenthal.raoa.elastic.model.*;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.AuthorizationManager;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

@Service
public class DefaultAuthorizationManager implements AuthorizationManager {
  private final Mono<UUID> latestAlbum;
  private final DataViewService dataViewService;

  public DefaultAuthorizationManager(
      final AlbumList albumList,
      final DataViewService dataViewService,
      final ViewerProperties viewerProperties) {
    if (viewerProperties.isAlwaysShowLatestRepository())
      latestAlbum =
          albumList
              .listAlbums()
              .flatMap(
                  a ->
                      a.getAccess()
                          .readAutoadd()
                          .reduce((t1, t2) -> t1.isAfter(t2) ? t1 : t2)
                          .map(d -> Tuples.of(d, a.getAlbumId())))
              .collect(Collectors.maxBy(Comparator.comparing(Tuple2::getT1)))
              .filter(Optional::isPresent)
              .map(Optional::get)
              .map(Tuple2::getT2)
              .cache(Duration.ofMinutes(5));
    else latestAlbum = Mono.just(UUID.randomUUID());
    this.dataViewService = dataViewService;
  }

  private static boolean membershipMatches(
      final GroupMembership groupMembership, final Instant createTime) {
    return Optional.ofNullable(groupMembership.getFrom())
            .map(from -> from.isBefore(createTime))
            .orElse(true)
        && Optional.ofNullable(groupMembership.getUntil())
            .map(until -> until.isAfter(createTime))
            .orElse(true);
  }

  @Override
  public boolean isUserAuthenticated(final SecurityContext context) {
    return currentAuthentication(context).isPresent();
  }

  @Override
  public Optional<AuthenticationId> currentAuthentication(SecurityContext context) {
    final Authentication authentication = context.getAuthentication();
    if (authentication == null) return Optional.empty();
    final Authentication userAuthentication;
    if (authentication instanceof OAuth2Authentication) {
      userAuthentication = ((OAuth2Authentication) authentication).getUserAuthentication();
    } else userAuthentication = authentication;
    final Object principal = userAuthentication.getPrincipal();

    if (principal instanceof Jwt) {
      final Map<String, Object> claims = ((Jwt) principal).getClaims();
      final String subject = (String) claims.get("sub");
      String authorizedClientRegistrationId = (String) claims.get("iss");
      return Optional.of(
          AuthenticationId.builder().authority(authorizedClientRegistrationId).id(subject).build());
    }

    return Optional.empty();
    /*
       if (principal instanceof DefaultOAuth2User
               && userAuthentication instanceof OAuth2AuthenticationToken) {
         final String authorizedClientRegistrationId =
                 ((OAuth2AuthenticationToken) userAuthentication).getAuthorizedClientRegistrationId();
         final String subject = (String) ((DefaultOAuth2User) principal).getAttributes().get("sub");
         return Optional.of(
                 AuthenticationId.builder().authority(authorizedClientRegistrationId).id(subject).build());
       } else return Optional.empty();

    */
  }

  @Override
  public Flux<AlbumData> findVisibleAlbumsOfUser(User user) {
    final Set<UUID> directVisibleAlbums = user.getVisibleAlbums();
    return dataViewService
        .listAlbums()
        .filterWhen(
            album -> {
              if (user.isSuperuser() || directVisibleAlbums.contains(album.getRepositoryId()))
                return Mono.just(true);
              final Instant createTime = album.getCreateTime();
              return Flux.fromIterable(user.getGroupMembership())
                  .filter(groupMembership -> membershipMatches(groupMembership, createTime))
                  .map(GroupMembership::getGroup)
                  .flatMap(dataViewService::findGroupById)
                  .any(g -> g.getVisibleAlbums().contains(album.getRepositoryId()));
            });
  }

  @Override
  public Mono<Boolean> canUserAccessToAlbum(final SecurityContext context, final UUID album) {
    return canUserAccessToAlbum(album, currentUser(context));
  }

  @Override
  public Mono<Boolean> canUserModifyAlbum(final SecurityContext context, final UUID album) {
    final Mono<User> currentUser = currentUser(context).cache();
    return Mono.zip(canUserAccessToAlbum(album, currentUser), currentUser.map(User::isEditor))
        .map(t -> t.getT1() && t.getT2());
  }

  @Override
  @NotNull
  public Mono<Boolean> canUserAccessToAlbum(final UUID album, final Mono<User> currentUser) {
    final Mono<Boolean> canAccessAlbum =
        currentUser
            .flatMap(
                u -> {
                  if (u.isSuperuser() || u.getVisibleAlbums().contains(album))
                    return Mono.just(true);

                  final Mono<Instant> createAlbumTimeMono =
                      dataViewService
                          .readAlbum(album)
                          .map(
                              albumData ->
                                  Optional.ofNullable(albumData.getCreateTime())
                                      .orElse(Instant.MIN))
                          .cache();
                  return Flux.fromIterable(u.getGroupMembership())
                      .flatMap(
                          groupMembership ->
                              dataViewService
                                  .findGroupById(groupMembership.getGroup())
                                  .filterWhen(
                                      group ->
                                          createAlbumTimeMono.map(
                                              createAlbumTime ->
                                                  membershipMatches(
                                                      groupMembership, createAlbumTime))))
                      .flatMapIterable(Group::getVisibleAlbums)
                      .any(album::equals);
                })
            .defaultIfEmpty(false);
    return Mono.zip(canAccessAlbum, latestAlbum.map(album::equals)).map(t -> t.getT1() || t.getT2())
    // .log("can access " + album)
    ;
  }

  @Override
  public Mono<Boolean> canUserManageUsers(final SecurityContext context) {
    return currentUser(context)
        .map(User::isSuperuser)
        // .log("can manage users")
        .defaultIfEmpty(false);
  }

  @Override
  @NotNull
  public Mono<User> currentUser(final SecurityContext context) {
    return currentAuthentication(context)
        .map(
            authenticationId ->
                dataViewService.findUserForAuthentication(authenticationId).singleOrEmpty())
        .orElse(Mono.empty());
  }

  @Override
  public PersonalUserData readPersonalUserData(final SecurityContext context) {
    final Object principal = context.getAuthentication().getPrincipal();
    final Jwt oidcUser = (Jwt) principal;
    final Map<String, Object> attributes = oidcUser.getClaims();
    return PersonalUserData.builder()
        .comment("")
        .picture((String) attributes.get("picture"))
        .name((String) attributes.get("name"))
        .email((String) attributes.get("email"))
        .emailVerified((Boolean) attributes.get("email_verified"))
        .build();
  }

  @Override
  public Mono<Boolean> hasPendingRequest(final SecurityContext context) {
    return Mono.justOrEmpty(currentAuthentication(context))
        .flatMap(id -> dataViewService.getPendingRequest(id).hasElement())
        .defaultIfEmpty(false);
  }
}
