package ch.bergturbenthal.raoa.elastic;

import ch.bergturbenthal.raoa.elastic.repository.AlbumDataRepository;
import ch.bergturbenthal.raoa.elastic.repository.SyncAlbumDataEntryRepository;
import ch.bergturbenthal.raoa.elastic.service.impl.ElasticSearchDataViewService;
import ch.bergturbenthal.raoa.libs.RaoaLibConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.Jsr310Converters;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.elasticsearch.core.convert.ElasticsearchConverter;
import org.springframework.data.elasticsearch.core.convert.ElasticsearchCustomConversions;
import org.springframework.data.elasticsearch.core.convert.MappingElasticsearchConverter;
import org.springframework.data.elasticsearch.core.mapping.SimpleElasticsearchMappingContext;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.data.elasticsearch.repository.config.EnableReactiveElasticsearchRepositories;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Configuration
@EnableReactiveElasticsearchRepositories(basePackageClasses = AlbumDataRepository.class)
@EnableElasticsearchRepositories(basePackageClasses = SyncAlbumDataEntryRepository.class)
@Import({ RaoaLibConfiguration.class })
@ComponentScan(basePackageClasses = ElasticSearchDataViewService.class)
@EnableConfigurationProperties({ ElasticsearchProperties.class })
public class RaoaElasticConfiguration {

    static {
        log.info("Init Class");
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] { new DummyX509TrustManager() }, null);
            SSLContext.setDefault(sslContext);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            log.error("Cannot override TLS settings");
            e.printStackTrace();
        }
    }

    public RaoaElasticConfiguration() {
        log.info("Init Instance");
    }

    @Bean
    public ElasticsearchCustomConversions elasticsearchCustomConversions() {
        Jsr310Converters.getConvertersToRegister();
        return new ElasticsearchCustomConversions(Stream.concat(
                Stream.of(new ObjectIdToString(), new StringToObjectId(), new InstantLongReader(),
                        new InstantIntegerReader(), new InstantDoubleReader()),
                Jsr310Converters.getConvertersToRegister().stream()).collect(Collectors.toList()));
    }

    @Bean
    ElasticsearchConverter elasticsearchConverter(SimpleElasticsearchMappingContext mappingContext,
            ElasticsearchCustomConversions customConversions) {
        final MappingElasticsearchConverter mappingElasticsearchConverter = new MappingElasticsearchConverter(
                mappingContext);
        mappingElasticsearchConverter.setConversions(customConversions);

        return mappingElasticsearchConverter;
    }

    @WritingConverter
    static class ObjectIdToString implements Converter<ObjectId, String> {

        @Override
        public String convert(final ObjectId source) {
            return source.name();
        }
    }

    @ReadingConverter
    static class StringToObjectId implements Converter<String, ObjectId> {

        @Override
        public ObjectId convert(final String source) {
            return ObjectId.fromString(source);
        }
    }

    @ReadingConverter
    static class InstantLongReader implements Converter<Long, Instant> {

        @Override
        public Instant convert(final Long source) {
            return Instant.ofEpochMilli(source);
        }
    }

    @ReadingConverter
    static class InstantIntegerReader implements Converter<Integer, Instant> {

        @Override
        public Instant convert(final Integer source) {
            return Instant.ofEpochSecond(source);
        }
    }

    @ReadingConverter
    static class InstantDoubleReader implements Converter<Double, Instant> {

        @Override
        public Instant convert(final Double source) {
            return Instant.ofEpochMilli(Math.round(source * 1000.0));
        }
    }
}
