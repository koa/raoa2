package ch.bergturbenthal.raoa.libs.model.kafka;

import java.util.UUID;
import lombok.Value;

@Value
public class ProcessImageRequest {
  UUID albumId;
  String filename;
}
