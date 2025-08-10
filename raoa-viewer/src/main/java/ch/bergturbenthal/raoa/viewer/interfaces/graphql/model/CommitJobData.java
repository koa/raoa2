package ch.bergturbenthal.raoa.viewer.interfaces.graphql.model;

import ch.bergturbenthal.raoa.elastic.model.CommitJob;
import lombok.Value;

@Value
public class CommitJobData {
    CommitJob data;
}
