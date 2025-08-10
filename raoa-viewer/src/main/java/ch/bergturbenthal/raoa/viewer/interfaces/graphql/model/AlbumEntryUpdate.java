package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import java.util.Set;
import lombok.Value;

@Value
public class AlbumEntryUpdate {
    Set<String> addKeywords;
    Set<String> removeKeywords;
}
