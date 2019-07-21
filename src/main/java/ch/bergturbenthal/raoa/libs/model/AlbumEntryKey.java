package ch.bergturbenthal.raoa.libs.model;

import java.util.UUID;
import lombok.Value;
import org.eclipse.jgit.lib.ObjectId;

@Value
public class AlbumEntryKey {
  private UUID album;
  private ObjectId entry;
}
