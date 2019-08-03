package ch.bergturbenthal.raoa.libs.service.impl.cache;

import ch.bergturbenthal.raoa.libs.service.impl.BareAlbumList;
import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.apache.tika.metadata.Metadata;
import org.ehcache.spi.serialization.Serializer;
import org.ehcache.spi.serialization.SerializerException;

public class MetadataSerializer implements Serializer<Metadata> {
  private static final Charset CHARSET = StandardCharsets.UTF_8;

  @Override
  public ByteBuffer serialize(final Metadata object) throws SerializerException {
    final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    final PrintWriter printWriter =
        new PrintWriter(new OutputStreamWriter(byteArrayOutputStream, CHARSET));
    for (String keyName : object.names()) {
      final String[] values = object.getValues(keyName);
      if (values.length > 0) {
        printWriter.print(URLEncoder.encode(keyName, CHARSET));
        for (String value : values) {
          printWriter.print(' ');
          printWriter.print(URLEncoder.encode(value, CHARSET));
        }
        printWriter.println();
      }
    }
    printWriter.close();
    return ByteBuffer.wrap(byteArrayOutputStream.toByteArray());
  }

  @Override
  public Metadata read(final ByteBuffer binary) throws ClassNotFoundException, SerializerException {
    final Metadata metadata = new Metadata();
    final BufferedReader bufferedReader =
        new BufferedReader(
            new InputStreamReader(new ByteArrayInputStream(binary.array()), CHARSET));
    try {
      while (true) {
        final String line = bufferedReader.readLine();
        if (line == null) break;
        final String[] parts = BareAlbumList.SPLIT_PATTERN.split(line);
        String key = URLDecoder.decode(parts[0], CHARSET);
        if (parts.length < 2) {
          metadata.add(key, "");
          continue;
        }
        for (int i = 1; i < parts.length; i++) {
          String value = URLDecoder.decode(parts[i], CHARSET);
          metadata.add(key, value);
        }
      }
      return metadata;
    } catch (IOException e) {
      throw new SerializerException("Cannot read data", e);
    }
  }

  @Override
  public boolean equals(final Metadata object, final ByteBuffer binary)
      throws ClassNotFoundException, SerializerException {
    return object.equals(read(binary));
  }
}
