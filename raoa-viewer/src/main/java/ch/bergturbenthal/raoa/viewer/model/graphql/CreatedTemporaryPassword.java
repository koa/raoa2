package ch.bergturbenthal.raoa.viewer.model.graphql;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.Value;

@Value
public class CreatedTemporaryPassword {
    UUID userId;
    String title;
    String password;
    OffsetDateTime validUntil;

    public static CreatedTemporaryPassword from(ch.bergturbenthal.raoa.elastic.model.TemporaryPassword model) {
        return new CreatedTemporaryPassword(model.getUserId(), model.getTitle(), model.getPassword(),
                model.getValidUntil().atOffset(ZoneOffset.UTC));
    }
}
