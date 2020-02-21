package ch.bergturbenthal.raoa.elastic.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Value
@Builder
@JsonDeserialize(builder = KeywordCount.KeywordCountBuilder.class)
public class KeywordCount {
  @Field(type = FieldType.Keyword)
  private String keyword;

  @Field(type = FieldType.Integer)
  private int entryCount;

  @JsonPOJOBuilder(withPrefix = "")
  public static class KeywordCountBuilder {}
}
