package ch.bergturbenthal.raoa.libs.service.impl;

import ch.bergturbenthal.raoa.libs.properties.Properties;
import ch.bergturbenthal.raoa.libs.service.ThumbnailFilenameService;
import ch.bergturbenthal.raoa.libs.service.UploadFilenameService;
import java.io.File;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DefaultThumbnailFilenameService implements ThumbnailFilenameService, UploadFilenameService {
    public static int[] SCALES = { 25, 50, 100, 200, 400, 800, 1600, 3200 };
    private final Properties properties;

    public DefaultThumbnailFilenameService(final Properties properties) {
        this.properties = properties;
    }

    @Override
    public File findThumbnailOf(final UUID album, final ObjectId entry, final int size) {
        return doFindThumbnail(album, entry, size, ".jpg");
    }

    @Override
    public File findVideoThumbnailOf(final UUID album, final ObjectId entry, final int size) {
        return doFindThumbnail(album, entry, size, ".mp4");
    }

    @NotNull
    private File doFindThumbnail(final UUID album, final ObjectId entry, final int size, final String ending) {
        for (int candidateSize : SCALES) {
            if (candidateSize >= size)
                return createThumbnailFile(album, entry, candidateSize, ending);
        }

        return createThumbnailFile(album, entry, 3200, ending);
    }

    @Override
    public Stream<FileAndScale> listThumbnailsOf(final UUID album, final ObjectId entry) {
        return Arrays.stream(SCALES).mapToObj(size -> new FileAndScale(createThumbnailFile(album, entry, size, ".jpg"),
                createThumbnailFile(album, entry, size, ".mp4"), size));
    }

    @NotNull
    private File createThumbnailFile(final UUID album, final ObjectId entryId, final int size, final String ending) {
        final String name = entryId.name();
        final String prefix = name.substring(0, 2);
        final String suffix = name.substring(2);
        final String targetFilename = album.toString() + "/" + size + "/" + prefix + "/" + suffix + ending;
        return new File(properties.getThumbnailDir(), targetFilename);
    }

    @Override
    public File createTempUploadFile(final UUID id) {
        return new File(getUploadDir(), id.toString());
    }

    @NotNull
    private File getUploadDir() {
        final File uploadDir = properties.getImportDir();
        if (!uploadDir.exists())
            uploadDir.mkdirs();
        return uploadDir;
    }

    @Scheduled(fixedDelay = 10 * 60 * 1000)
    public void cleanupOldUploads() {
        final long thresholdTime = System.currentTimeMillis() - 24 * 3600 * 1000;
        final File[] foundFiles = getUploadDir().listFiles(f -> f.lastModified() < thresholdTime);
        if (foundFiles != null)
            for (File file : foundFiles) {
                file.delete();
            }
    }
}
