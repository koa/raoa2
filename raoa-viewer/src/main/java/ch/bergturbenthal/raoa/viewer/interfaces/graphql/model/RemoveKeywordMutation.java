package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import java.util.UUID;
import lombok.Value;

@Value
public class RemoveKeywordMutation {
  UUID albumId;
  String albumEntryId;
  String keyword;
}
