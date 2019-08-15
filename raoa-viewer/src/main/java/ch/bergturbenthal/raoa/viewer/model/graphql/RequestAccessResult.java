package ch.bergturbenthal.raoa.viewer.model.graphql;

public interface RequestAccessResult {
  boolean isOk();

  RequestAccessResultCode getResult();
}
