package ch.bergturbenthal.raoa.elastic.model.serializer;

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
    gen.writeString(value.name());
  }
}
