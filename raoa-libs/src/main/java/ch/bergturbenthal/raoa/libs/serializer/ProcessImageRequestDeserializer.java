package ch.bergturbenthal.raoa.libs.serializer;

import ch.bergturbenthal.raoa.libs.model.kafka.ProcessImageRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.common.serialization.Deserializer;

public class ProcessImageRequestDeserializer implements Deserializer<ProcessImageRequest> {
  @Override
  public ProcessImageRequest deserialize(final String topic, final byte[] data) {
    final ByteBuffer byteBuffer = ByteBuffer.wrap(data);
    long msb = byteBuffer.getLong();
    long lsb = byteBuffer.getLong();
    final int byteCount = byteBuffer.getInt();
    byte[] stringContent = new byte[byteCount];
    byteBuffer.get(stringContent);
    return new ProcessImageRequest(
        new UUID(msb, lsb), new String(stringContent, StandardCharsets.UTF_8));
  }
}
