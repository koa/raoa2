package ch.bergturbenthal.raoa.sync.config;

import com.apollographql.apollo.api.CustomTypeAdapter;
import com.apollographql.apollo.api.CustomTypeValue;
import java.time.Instant;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

@Component
public class DateGraphQLAdapter implements CustomTypeAdapter<Instant> {

  @SneakyThrows
  @Override
  public Instant decode(@NotNull final CustomTypeValue<?> customTypeValue) {
    return Instant.parse(customTypeValue.value.toString());
  }

  @NotNull
  @Override
  public CustomTypeValue<?> encode(final Instant date) {
    return new CustomTypeValue.GraphQLString(date.toString());
  }
}
