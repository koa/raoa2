package ch.bergturbenthal.raoa.libs.model.kafka;

import java.util.UUID;
import lombok.Value;

@Value
public class ProcessImageRequest {
  private UUID albumId;
  private String filename;
}
