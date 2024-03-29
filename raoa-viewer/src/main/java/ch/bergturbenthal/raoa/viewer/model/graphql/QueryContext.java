package ch.bergturbenthal.raoa.viewer.model.graphql;

import ch.bergturbenthal.raoa.elastic.model.AuthenticationId;
import ch.bergturbenthal.raoa.elastic.model.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.context.request.RequestAttributes;
import reactor.core.publisher.Mono;

public interface QueryContext {
  SecurityContext getSecurityContext();

  Mono<AuthenticationState> getAuthenticationState();

  Optional<User> getCurrentUser();

  Optional<AuthenticationId> currentAuthenticationId();

  boolean canUserManageUsers();

  boolean canUserEditData();

  RequestAttributes getRequestAttributes();

  String getContexRootPath();

  boolean canAccessGroup(UUID groupId);
}
