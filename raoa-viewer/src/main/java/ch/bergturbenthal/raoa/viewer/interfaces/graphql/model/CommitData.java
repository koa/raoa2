package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CommitData {
    UUID albumId;
    List<ImportFile> files;
}
