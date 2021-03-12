package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import ch.bergturbenthal.raoa.viewer.model.graphql.LabelValueInput;
import java.util.List;
import java.util.Set;
import lombok.Value;

@Value
public class GroupUpdate {
  String newName;
  List<LabelValueInput> newLabels;
  Set<String> removeLabels;
}
