package ch.bergturbenthal.raoa.libs.serializer;

import org.apache.kafka.common.serialization.Deserializer;
import org.eclipse.jgit.lib.ObjectId;

public class ObjectIdDeserializer implements Deserializer<ObjectId> {
  @Override
  public ObjectId deserialize(final String topic, final byte[] data) {
    return ObjectId.fromRaw(data);
  }
}
