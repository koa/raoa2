package ch.bergturbenthal.raoa.viewer.model.graphql;

import ch.bergturbenthal.raoa.viewer.model.usermanager.AuthenticationId;
import ch.bergturbenthal.raoa.viewer.model.usermanager.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.context.request.RequestAttributes;

public interface QueryContext {
  SecurityContext getSecurityContext();

  AuthenticationState getAuthenticationState();

  Optional<User> getCurrentUser();

  Optional<AuthenticationId> currentAuthenticationId();

  boolean canUserManageUsers();

  boolean canAccessAlbum(UUID albumId);

  RequestAttributes getRequestAttributes();
}
