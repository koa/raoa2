package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import java.util.UUID;
import lombok.Value;

@Value
public class AddKeywordMutation {
  UUID albumId;
  String albumEntryId;
  String keyword;
}
