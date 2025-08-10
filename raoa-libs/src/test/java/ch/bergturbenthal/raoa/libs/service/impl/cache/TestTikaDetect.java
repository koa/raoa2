package ch.bergturbenthal.raoa.libs.service.impl.cache;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

public class TestTikaDetect {
    @Test
    public void testTikaDetect() throws Exception {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler();
        Metadata metadata = new Metadata();
        final Path file = Path.of("/tmp", "fa216263-0367-46c5-99b5-3402aa745703");
        final TikaInputStream inputStream = TikaInputStream.get(file);
        parser.parse(inputStream, handler, metadata);
        System.out.println(metadata.get(Metadata.CONTENT_TYPE));
    }
}
