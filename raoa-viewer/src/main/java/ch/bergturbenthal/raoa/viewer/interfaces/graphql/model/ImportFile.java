package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import java.util.UUID;
import lombok.Value;

@Value
public class ImportFile {
  UUID fileId;
  String filename;
  long size;
}
