package ch.bergturbenthal.raoa.processor.image.test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import javax.imageio.ImageIO;

public class TestReadRawImage {
  public static void main(String[] args) throws IOException {
    final long startTime = System.nanoTime();
    final Process process =
        Runtime.getRuntime().exec(new String[] {"dcraw", "-c", "/tmp/_DSC7721.NEF"});
    final InputStream inputStream = process.getInputStream();
    final BufferedImage read = ImageIO.read(inputStream);
    ImageIO.write(read, "jpg", new File("/tmp/out.jpg"));
    System.out.println("Duration: " + Duration.ofNanos(System.nanoTime() - startTime));
  }
}
