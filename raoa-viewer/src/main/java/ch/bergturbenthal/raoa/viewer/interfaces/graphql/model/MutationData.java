package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import lombok.Value;

@Value
public class MutationData {
  AddKeywordMutation addKeywordMutation;
  RemoveKeywordMutation removeKeywordMutation;
}
