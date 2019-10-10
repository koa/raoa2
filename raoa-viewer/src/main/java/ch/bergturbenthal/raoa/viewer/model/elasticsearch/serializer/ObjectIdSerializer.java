package ch.bergturbenthal.raoa.viewer.model.elasticsearch.serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import org.eclipse.jgit.lib.ObjectId;

public class ObjectIdSerializer extends JsonSerializer<ObjectId> {
  @Override
  public void serialize(
      final ObjectId value, final JsonGenerator gen, final SerializerProvider serializers)
      throws IOException {
    /*byte[] data = new byte[20];
    value.copyRawTo(data, 0);
    gen.writeBinary(data);

     */
    gen.writeString(value.name());
  }
}
