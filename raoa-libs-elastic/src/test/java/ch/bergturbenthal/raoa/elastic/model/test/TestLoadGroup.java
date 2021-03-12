package ch.bergturbenthal.raoa.elastic.model.test;

import ch.bergturbenthal.raoa.elastic.model.Group;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.util.Collections;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

@Slf4j
public class TestLoadGroup {
  @Test
  public void testLoadGroup() throws IOException {
    final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().indentOutput(true).build();

    final ObjectReader groupReader = objectMapper.readerFor(Group.class);
    // final ObjectWriter groupWriter = objectMapper.writerFor(Group.class);

    final Group group = groupReader.readValue(new ClassPathResource("group.json").getInputStream());
    Assert.assertEquals(
        group.getLabels(), Collections.singletonMap("fnch-competitor-id", "225856"));
  }
}
