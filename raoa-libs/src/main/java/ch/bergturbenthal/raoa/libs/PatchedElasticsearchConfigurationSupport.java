package ch.bergturbenthal.raoa.libs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.config.ElasticsearchConfigurationSupport;
import org.springframework.data.elasticsearch.core.EntityMapper;

@Slf4j
@Configuration
public class PatchedElasticsearchConfigurationSupport extends ElasticsearchConfigurationSupport {
  @Override
  public EntityMapper entityMapper() {
    log.info("Create entity mapper");
    return new CustomEntityMapper(elasticsearchMappingContext());
  }
}
