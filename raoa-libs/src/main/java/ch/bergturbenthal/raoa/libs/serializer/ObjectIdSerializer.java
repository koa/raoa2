package ch.bergturbenthal.raoa.libs.serializer;

import java.nio.ByteBuffer;
import org.apache.kafka.common.serialization.Serializer;
import org.eclipse.jgit.lib.ObjectId;

public class ObjectIdSerializer implements Serializer<ObjectId> {
  @Override
  public byte[] serialize(final String topic, final ObjectId data) {
    final ByteBuffer buffer = ByteBuffer.allocate(20);
    data.copyRawTo(buffer);
    return buffer.array();
  }
}
