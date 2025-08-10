package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Value
public class MutationData {
    AddKeywordMutation addKeywordMutation;
    RemoveKeywordMutation removeKeywordMutation;
}
