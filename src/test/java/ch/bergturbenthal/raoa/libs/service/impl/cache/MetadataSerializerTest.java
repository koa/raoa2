package ch.bergturbenthal.raoa.libs.service.impl.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.core.io.ClassPathResource;
import org.xml.sax.SAXException;

class MetadataSerializerTest {

  @org.junit.jupiter.api.Test
  void testProcessFileMetadata()
      throws IOException, TikaException, SAXException, ClassNotFoundException {
    final ClassPathResource resource = new ClassPathResource("2019-06-29-08-44-24-_DSC0742.JPG");
    AutoDetectParser parser = new AutoDetectParser();
    BodyContentHandler handler = new BodyContentHandler();
    Metadata metadata = new Metadata();
    final TikaInputStream inputStream = TikaInputStream.get(resource.getURL());
    parser.parse(inputStream, handler, metadata);

    final MetadataSerializer serializer = new MetadataSerializer();
    final ByteBuffer serialData = serializer.serialize(metadata);
    final Metadata takenMetadata = serializer.read(serialData);
    Set<String> originalNames = new HashSet<>(Arrays.asList(metadata.names()));
    Set<String> newNames = new HashSet<>(Arrays.asList(takenMetadata.names()));
    assertEquals(originalNames, newNames);
    assertEquals(metadata, takenMetadata);
    assertTrue(serializer.equals(metadata, serialData));
  }
}
