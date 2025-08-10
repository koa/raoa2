package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import ch.bergturbenthal.raoa.viewer.model.graphql.AlbumEntry;
import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MutationResult {
    List<MutationError> errors;
    List<AlbumEntry> modifiedEntries;
}
