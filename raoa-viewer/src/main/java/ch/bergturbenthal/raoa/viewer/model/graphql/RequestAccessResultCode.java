package ch.bergturbenthal.raoa.viewer.model.graphql;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum RequestAccessResultCode {
  OK(true),
  NOT_LOGGED_IN(false),
  ALREADY_ACCEPTED(false);
  @Getter private final boolean ok;
}
