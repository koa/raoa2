package ch.bergturbenthal.raoa.thumbnailer.image;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ThumbnailerProperties.class)
public class ImageThumbnailerApplication {

  public static void main(String[] args) {
    SpringApplication.run(ImageThumbnailerApplication.class, args);
  }
}
