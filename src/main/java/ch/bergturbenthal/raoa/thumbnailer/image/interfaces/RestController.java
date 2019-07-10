package ch.bergturbenthal.raoa.thumbnailer.image.interfaces;

import ch.bergturbenthal.raoa.thumbnailer.image.ThumbnailerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TIFF;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.xml.sax.SAXException;

@Slf4j
@Controller
public class RestController {
  private final ThumbnailerProperties thumbnailerProperties;
  private final AtomicInteger currentRunning = new AtomicInteger(0);
  private final MeterRegistry meterRegistry;
  private Map<String, Instant> pendingTokens = new ConcurrentHashMap<>();

  public RestController(
      final ThumbnailerProperties thumbnailerProperties, final MeterRegistry meterRegistry) {
    this.thumbnailerProperties = thumbnailerProperties;
    this.meterRegistry = meterRegistry;
    meterRegistry.gauge("raoa.thumbnail.running", currentRunning);
    meterRegistry.gauge(
        "raoa.thumbnail.limit", thumbnailerProperties, ThumbnailerProperties::getConcurrent);
  }

  @GetMapping("mediatypes")
  public @ResponseBody List<String> listMediaTypes() {
    return Arrays.asList("image/jpeg");
  }

  @GetMapping("token")
  public synchronized @ResponseBody String getToken() {
    final Instant now = Instant.now();
    pendingTokens.values().removeIf(now::isAfter);
    if (currentRunning.get() + pendingTokens.size() > thumbnailerProperties.getConcurrent()) {
      log.info("Overloaded");
      return "";
    }
    String token = UUID.randomUUID().toString();
    pendingTokens.put(token, now.plusSeconds(10));
    return token;
  }

  @PostMapping(value = "thumbnail", consumes = "image/jpeg", produces = "image/jpeg")
  public void createThumbnail(
      @RequestParam(name = "size", defaultValue = "1600") int size,
      @RequestParam("token") String token,
      InputStream request,
      HttpServletResponse response)
      throws TikaException, SAXException, IOException {

    currentRunning.incrementAndGet();

    try {
      final Instant tokenValidity = pendingTokens.remove(token);
      if (tokenValidity == null) {
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "invalid token");
        return;
      }
      Instant startTime = Instant.now();

      final File tempFile = File.createTempFile("input-", ".jpg");
      try (final FileOutputStream output = new FileOutputStream(tempFile)) {
        IOUtils.copy(request, output);
      }
      // log.info("Data size: " + tempFile.length());

      AutoDetectParser parser = new AutoDetectParser();
      BodyContentHandler handler = new BodyContentHandler();
      Metadata metadata = new Metadata();
      final TikaInputStream inputStream = TikaInputStream.get(tempFile.toPath());
      parser.parse(inputStream, handler, metadata);
      final String anInt = metadata.get(TIFF.ORIENTATION);
      final int orientation = Optional.ofNullable(anInt).map(Integer::parseInt).orElse(1);

      AffineTransform t = new AffineTransform();
      final BufferedImage inputImage = ImageIO.read(tempFile);

      final int width = inputImage.getWidth();
      final int height = inputImage.getHeight();
      final boolean flip;
      int maxLength = Math.max(width, height);
      double scale = size * 1.0 / maxLength;
      switch (orientation) {
        default:
        case 1:
          flip = false;
          break;
        case 2: // Flip X
          flip = false;
          t.scale(-1.0, 1.0);
          t.translate(-width * scale, 0);
          break;
        case 3: // PI rotation
          flip = false;
          t.translate(width * scale, height * scale);
          t.quadrantRotate(2);
          break;
        case 4: // Flip Y
          flip = false;
          t.scale(1.0, -1.0);
          t.translate(0, -height * scale);
          break;
        case 5: // - PI/2 and Flip X
          flip = true;
          t.quadrantRotate(3);
          t.scale(-1.0, 1.0);
          break;
        case 6: // -PI/2 and -width
          flip = true;
          t.translate(height * scale, 0);
          t.quadrantRotate(1);
          break;
        case 7: // PI/2 and Flip
          flip = true;
          t.scale(-1.0, 1.0);
          t.translate(-height * scale, 0);
          t.translate(0, width * scale);
          t.quadrantRotate(3);
          break;
        case 8: // PI / 2
          flip = true;
          t.translate(0, width * scale);
          t.quadrantRotate(3);
          break;
      }
      t.scale(scale, scale);
      int targetWith;
      int targetHeight;
      if (flip) {
        targetWith = (int) (height * scale);
        targetHeight = (int) (width * scale);
      } else {
        targetWith = (int) (width * scale);
        targetHeight = (int) (height * scale);
      }
      BufferedImage targetImage =
          new BufferedImage(targetWith, targetHeight, BufferedImage.TYPE_INT_RGB);
      Graphics2D graphics = targetImage.createGraphics();
      graphics.setTransform(t);
      graphics.drawImage(inputImage, 0, 0, null);
      graphics.dispose();
      response.setContentType("image/jpeg");
      ImageIO.write(targetImage, "jpg", response.getOutputStream());
      tempFile.delete();
      timer("process").record(Duration.between(startTime, Instant.now()));
    } finally {
      currentRunning.decrementAndGet();
    }
  }

  private Timer timer(final String resultCode) {
    return meterRegistry.timer("raoa.thumbnail.run", "result", resultCode);
  }
}
