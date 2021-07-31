package ch.bergturbenthal.raoa.processor.image.test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.Arrays;
import lombok.Cleanup;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TIFF;
import org.apache.tika.metadata.XMPDM;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;

public class TestProcessVideo {
  public static void main(String[] args)
      throws IOException, TikaException, SAXException, InterruptedException {

    final AutoDetectParser parser = new AutoDetectParser();

    BodyContentHandler handler = new BodyContentHandler();
    Metadata metadata = new Metadata();

    final File file =
        new File("/tmp/c093fab3-0a12-51e6-ba81-daac95c281ef/2021-05-11-13-47-31-DSC_0048.MP4");
    final Path path = file.toPath();

    // log.info("Path " + path + " exists: " +
    // Files.exists(path));
    @Cleanup final TikaInputStream inputStream = TikaInputStream.get(path);
    parser.parse(inputStream, handler, metadata);
    for (String name : metadata.names()) {
      final String value = metadata.get(name);
      System.out.println(name + ": " + value);
    }
    final String contentType = metadata.get(HttpHeaders.CONTENT_TYPE);
    for (int length : Arrays.asList(50, 100, 200, 400, 800, 1600, 3200))
      if (contentType.startsWith("video/")) {
        double duration = Double.parseDouble(metadata.get(XMPDM.DURATION));
        final Integer width = metadata.getInt(TIFF.IMAGE_WIDTH);
        final Integer height = metadata.getInt(TIFF.IMAGE_LENGTH);
        int targetLength = Math.min(length, Math.max(width, height));
        final NumberFormat numberInstance = NumberFormat.getNumberInstance();
        numberInstance.setMaximumFractionDigits(3);
        numberInstance.setMinimumFractionDigits(3);
        final String thumbnailPos = numberInstance.format(duration / 3);

        System.out.println("Duration: " + duration);
        System.out.println("Pos: " + thumbnailPos);
        final String scale;
        if (width > height) {
          int targetHeight = targetLength * height / width;
          scale = targetLength + ":" + (targetHeight - targetHeight % 2);
        } else {
          int targetWidth = targetLength * width / height;
          scale = (targetWidth - targetWidth % 2) + ":" + targetLength;
        }
        System.out.println("Scale: " + scale);
        execute(
            new String[] {
              "ffmpeg",
              "-y",
              "-i",
              file.getAbsolutePath(),
              "-vf",
              "scale=" + scale,
              "/tmp/output-" + length + ".mp4"
            });
        execute(
            new String[] {
              "ffmpeg",
              "-y",
              "-i",
              file.getAbsolutePath(),
              "-ss",
              thumbnailPos,
              "-vframes",
              "1",
              "-vf",
              "scale=" + scale,
              "/tmp/output-" + length + ".jpg"
            });
      }
  }

  private static void execute(final String[] cmdarray) throws IOException, InterruptedException {
    final Process process = Runtime.getRuntime().exec(cmdarray);
    new Thread(
            () -> {
              try {
                final BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()));

                while (true) {
                  final String line = reader.readLine();
                  if (line == null) break;
                  System.out.println("S: " + line);
                }
              } catch (IOException e) {
                e.printStackTrace();
              }
            })
        .start();
    new Thread(
            () -> {
              try {
                final BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getErrorStream()));

                while (true) {
                  final String line = reader.readLine();
                  if (line == null) break;
                  System.out.println("E: " + line);
                }
              } catch (IOException e) {
                e.printStackTrace();
              }
            })
        .start();

    final int exitValue = process.waitFor();
    System.out.println("Exit: " + exitValue);
  }
}
