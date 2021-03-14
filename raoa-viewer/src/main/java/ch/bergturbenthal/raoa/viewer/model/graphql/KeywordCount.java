package ch.bergturbenthal.raoa.viewer.model.graphql;

import lombok.Value;

@Value
public class KeywordCount {
  String keyword;
  int count;
}
