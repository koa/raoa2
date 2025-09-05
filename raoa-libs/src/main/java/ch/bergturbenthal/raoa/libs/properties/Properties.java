package ch.bergturbenthal.raoa.libs.properties;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.lang.NonNull;
import org.springframework.validation.annotation.Validated;

import java.io.File;
import java.util.TimeZone;

@Slf4j
@ConfigurationProperties(prefix = "raoa")
@Data
@Validated
public class Properties {
    @NonNull
    private File repository;
    @NonNull
    private File thumbnailDir;
    @NonNull
    private File importDir;
    private int maxConcurrent = 30;
    private int asyncThreadCount = 10;
    private String superuser = "107024483334418897627";
    private TimeZone timeZone = TimeZone.getDefault();
}
