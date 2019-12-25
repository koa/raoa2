package ch.bergturbenthal.raoa.libs.serializer;

import ch.bergturbenthal.raoa.libs.model.kafka.ProcessImageRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.serialization.Serializer;

public class ProcessImageRequestSerializer implements Serializer<ProcessImageRequest> {
  @Override
  public byte[] serialize(final String topic, final ProcessImageRequest data) {
    final byte[] bytes = data.getFilename().getBytes(StandardCharsets.UTF_8);
    final ByteBuffer byteBuffer =
        ByteBuffer.allocate(Long.BYTES * 2 + Integer.BYTES + bytes.length);
    byteBuffer.putLong(data.getAlbumId().getMostSignificantBits());
    byteBuffer.putLong(data.getAlbumId().getLeastSignificantBits());
    byteBuffer.putInt(bytes.length);
    byteBuffer.put(bytes);
    return byteBuffer.array();
  }
}
