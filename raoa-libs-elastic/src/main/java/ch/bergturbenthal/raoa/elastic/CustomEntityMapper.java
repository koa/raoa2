package ch.bergturbenthal.raoa.elastic;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.elasticsearch.core.DefaultEntityMapper;
import org.springframework.data.elasticsearch.core.EntityMapper;
import org.springframework.data.elasticsearch.core.geo.CustomGeoModule;
import org.springframework.data.elasticsearch.core.mapping.ElasticsearchPersistentEntity;
import org.springframework.data.elasticsearch.core.mapping.ElasticsearchPersistentProperty;
import org.springframework.data.mapping.MappingException;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.util.Assert;

public class CustomEntityMapper implements EntityMapper {

  private ObjectMapper objectMapper;

  /**
   * Creates a new {@link DefaultEntityMapper} using the given {@link MappingContext}.
   *
   * @param context must not be {@literal null}.
   */
  public CustomEntityMapper(
      MappingContext<? extends ElasticsearchPersistentEntity<?>, ElasticsearchPersistentProperty>
          context) {

    Assert.notNull(context, "MappingContext must not be null!");

    objectMapper =
        Jackson2ObjectMapperBuilder.json()
            .modulesToInstall(new SpringDataElasticsearchModule(context), new CustomGeoModule())
            .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .featuresToEnable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .build();
  }

  /*
   * (non-Javadoc)
   * @see org.springframework.data.elasticsearch.core.EntityMapper#mapToString(java.lang.Object)
   */
  @Override
  public String mapToString(Object object) throws IOException {
    return objectMapper.writeValueAsString(object);
  }

  /*
   * (non-Javadoc)
   * @see org.springframework.data.elasticsearch.core.EntityMapper#mapObject(java.lang.Object)
   */
  @Override
  public Map<String, Object> mapObject(Object source) {

    try {
      return objectMapper.readValue(mapToString(source), HashMap.class);
    } catch (IOException e) {
      throw new MappingException(e.getMessage(), e);
    }
  }

  /*
   * (non-Javadoc)
   * @see org.springframework.data.elasticsearch.core.EntityMapper#mapToObject(java.lang.String, java.lang.Class)
   */
  @Override
  public <T> T mapToObject(String source, Class<T> clazz) throws IOException {
    return objectMapper.readValue(source, clazz);
  }

  /*
   * (non-Javadoc)
   * @see org.springframework.data.elasticsearch.core.EntityMapper#readObject(java.util.Map, java.lang.Class)
   */
  @Override
  public <T> T readObject(Map<String, Object> source, Class<T> targetType) {

    try {
      return mapToObject(mapToString(source), targetType);
    } catch (IOException e) {
      throw new MappingException(e.getMessage(), e);
    }
  }

  /**
   * @author Oliver Gierke
   * @since 3.1
   */
  private static class SpringDataElasticsearchModule extends SimpleModule {

    private static final long serialVersionUID = -9168968092458058966L;

    /** @param context must not be {@literal null}. */
    public SpringDataElasticsearchModule(
        MappingContext<? extends ElasticsearchPersistentEntity<?>, ElasticsearchPersistentProperty>
            context) {

      Assert.notNull(context, "MappingContext must not be null!");

      setSerializerModifier(
          new CustomEntityMapper.SpringDataElasticsearchModule.SpringDataSerializerModifier(
              context));
    }

    /**
     * A {@link BeanSerializerModifier} that will drop properties annotated with {@link
     * ReadOnlyProperty} for serialization.
     *
     * @author Oliver Gierke
     * @since 3.1
     */
    private static class SpringDataSerializerModifier extends BeanSerializerModifier {

      private final MappingContext<
              ? extends ElasticsearchPersistentEntity<?>, ElasticsearchPersistentProperty>
          context;

      public SpringDataSerializerModifier(
          MappingContext<
                  ? extends ElasticsearchPersistentEntity<?>, ElasticsearchPersistentProperty>
              context) {

        Assert.notNull(context, "MappingContext must not be null!");

        this.context = context;
      }

      /*
       * (non-Javadoc)
       * @see com.fasterxml.jackson.databind.ser.BeanSerializerModifier#changeProperties(com.fasterxml.jackson.databind.SerializationConfig, com.fasterxml.jackson.databind.BeanDescription, java.util.List)
       */
      @Override
      public List<BeanPropertyWriter> changeProperties(
          SerializationConfig config,
          BeanDescription description,
          List<BeanPropertyWriter> properties) {

        Class<?> type = description.getBeanClass();
        ElasticsearchPersistentEntity<?> entity = context.getPersistentEntity(type);

        if (entity == null) {
          return super.changeProperties(config, description, properties);
        }

        List<BeanPropertyWriter> result = new ArrayList<>(properties.size());

        for (BeanPropertyWriter beanPropertyWriter : properties) {

          ElasticsearchPersistentProperty property =
              entity.getPersistentProperty(beanPropertyWriter.getName());

          if (property != null && property.isWritable()) {
            result.add(beanPropertyWriter);
          }
        }

        return result;
      }
    }
  }
}
