package ch.bergturbenthal.raoa.viewer.service.impl;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

public class GitBlobRessource implements Resource {

  private final ObjectLoader objectLoader;
  private final MediaType mediaType;
  private final ObjectId id;

  public GitBlobRessource(
      final ObjectLoader objectLoader, final MediaType mediaType, final ObjectId id) {
    this.objectLoader = objectLoader;
    this.mediaType = mediaType;
    this.id = id;
  }

  @Override
  public InputStream getInputStream() throws IOException {
    return objectLoader.openStream();
  }

  @Override
  public boolean exists() {
    return true;
  }

  @Override
  public URL getURL() throws IOException {
    throw new IOException("URL not defined");
  }

  @Override
  public URI getURI() throws IOException {
    throw new IOException("URL not defined");
  }

  @Override
  public File getFile() throws IOException {
    throw new FileNotFoundException();
  }

  @Override
  public long contentLength() throws IOException {
    return objectLoader.getSize();
  }

  @Override
  public long lastModified() throws IOException {
    return System.currentTimeMillis();
  }

  @Override
  public Resource createRelative(final String relativePath) throws IOException {
    throw new IOException();
  }

  @Override
  public String getFilename() {
    if (mediaType.equals(MediaType.IMAGE_JPEG)) {
      return id.toString() + ".jpg";
    }
    return null;
  }

  @Override
  public String getDescription() {
    return id.toString();
  }
}
