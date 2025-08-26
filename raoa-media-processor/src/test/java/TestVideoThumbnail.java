import ch.bergturbenthal.raoa.libs.util.TikaUtil;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

public class TestVideoThumbnail {
    public static void main(final String[] args) throws IOException, TikaException, SAXException {
        final Path path = new File("/tmp/Kadertraining August 2025/2025-08-17-09-22-20-DSC_9583.MP4").toPath();
        BodyContentHandler handler = new BodyContentHandler();
        Metadata metadata = new Metadata();
        final AutoDetectParser parser = new AutoDetectParser();
        TikaInputStream inputStream = TikaInputStream.get(path);
        parser.parse(inputStream, handler, metadata);
        final Optional<Duration> durationString = TikaUtil.extractVideoDuration(metadata);
        final Optional<Integer> width = TikaUtil.extractTargetWidth(metadata);
        final Optional<Integer> height = TikaUtil.extractTargetHeight(metadata);
        System.out.println("Duration: " + durationString);
        System.out.println("Width: " + width);
        System.out.println("Height: " + height);
        for (final String name : metadata.names()) {
            System.out.println(name + ": " + metadata.get(name));
        }
    }
}
