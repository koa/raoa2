package ch.bergturbenthal.raoa.viewer.model.graphql;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.Value;

@Value
public class TemporaryPassword {
    UUID userId;
    String title;
    OffsetDateTime validUntil;

    public static TemporaryPassword from(ch.bergturbenthal.raoa.elastic.model.TemporaryPassword model) {
        return new TemporaryPassword(model.getUserId(), model.getTitle(),
                model.getValidUntil().atOffset(ZoneOffset.UTC));
    }
}
