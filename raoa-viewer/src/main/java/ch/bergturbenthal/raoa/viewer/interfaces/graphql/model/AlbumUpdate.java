package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import ch.bergturbenthal.raoa.viewer.model.graphql.LabelValueInput;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Value;

@Value
public class AlbumUpdate {
  String newAlbumTitle;
  String newTitleEntry;
  List<LabelValueInput> newLabels;
  List<String> removeLabels;
  List<OffsetDateTime> autoadd;
}
