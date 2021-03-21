package ch.bergturbenthal.raoa.libs.model;

import java.util.UUID;
import lombok.Value;

@Value
public class UploadResult {
  UUID fileId;
  long byteCount;
}
