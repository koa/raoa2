package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import java.util.UUID;
import lombok.NonNull;
import lombok.Value;

@Value
public class MutationError {
    UUID albumId;
    String albumEntryId;
    @NonNull
    String message;
}
