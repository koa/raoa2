package ch.bergturbenthal.raoa.libs.service.impl.cache;

import ch.bergturbenthal.raoa.libs.model.AlbumEntryKey;
import java.nio.ByteBuffer;
import java.util.UUID;
import org.eclipse.jgit.lib.ObjectId;
import org.ehcache.spi.serialization.Serializer;
import org.ehcache.spi.serialization.SerializerException;

public class AlbumEntryKeySerializer implements Serializer<AlbumEntryKey> {
  @Override
  public ByteBuffer serialize(final AlbumEntryKey object) throws SerializerException {
    final ByteBuffer buffer = ByteBuffer.allocate(36);
    buffer.putLong(object.getAlbum().getMostSignificantBits());
    buffer.putLong(object.getAlbum().getLeastSignificantBits());
    object.getEntry().copyRawTo(buffer);
    buffer.rewind();
    return buffer;
  }

  @Override
  public AlbumEntryKey read(final ByteBuffer binary) throws SerializerException {
    final long msb = binary.getLong();
    final long lsb = binary.getLong();
    final UUID albumId = new UUID(msb, lsb);
    byte[] buffer = new byte[20];
    binary.get(buffer);
    final ObjectId entryId = ObjectId.fromRaw(buffer);
    return new AlbumEntryKey(albumId, entryId);
  }

  @Override
  public boolean equals(final AlbumEntryKey object, final ByteBuffer binary)
      throws ClassNotFoundException, SerializerException {
    return object.equals(read(binary));
  }
}
