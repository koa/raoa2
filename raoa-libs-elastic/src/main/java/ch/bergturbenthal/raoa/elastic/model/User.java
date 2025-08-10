package ch.bergturbenthal.raoa.elastic.model;

import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "user_1", createIndex = true)
@Value
@Builder(toBuilder = true)
public class User {
    @Id
    UUID id;

    @Field(type = FieldType.Object)
    PersonalUserData userData;

    @Field(type = FieldType.Keyword)
    Set<UUID> visibleAlbums;

    @Field(type = FieldType.Object)
    Set<GroupMembership> groupMembership;

    @Field(type = FieldType.Object)
    Set<AuthenticationId> authentications;

    @Field(type = FieldType.Boolean)
    boolean superuser;

    @Field(type = FieldType.Boolean)
    boolean editor;
}
