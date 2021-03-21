package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import ch.bergturbenthal.raoa.viewer.model.graphql.AlbumEntry;
import java.util.UUID;
import lombok.Value;

@Value
public class ImportedFile {
  UUID fileId;
  AlbumEntry albumEntry;
}
