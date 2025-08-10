package ch.bergturbenthal.raoa.elastic.model;

import lombok.Builder;
import lombok.Value;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Value
@Builder(toBuilder = true)
public class PersonalUserData {
    @Field(type = FieldType.Text)
    private String name;

    @Field(type = FieldType.Keyword)
    private String picture;

    @Field(type = FieldType.Text)
    private String comment;

    @Field(type = FieldType.Text)
    private String email;

    @Field(type = FieldType.Boolean)
    private boolean emailVerified;
}
