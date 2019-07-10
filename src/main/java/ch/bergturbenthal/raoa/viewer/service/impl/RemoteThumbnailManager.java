package ch.bergturbenthal.raoa.viewer.service.impl;

import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import ch.bergturbenthal.raoa.viewer.service.ThumbnailManager;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.Cleanup;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

@Slf4j
@Service
public class RemoteThumbnailManager implements ThumbnailManager {

  private final WebClient webClient;
  private final Random random = new Random();
  private final AtomicReference<CurrentEndpointData> endpointData =
      new AtomicReference<>(
          new CurrentEndpointData(Collections.emptyMap(), Collections.emptySet()));
  private final Map<URI, Instant> blockUntil = new ConcurrentHashMap<>();
  private final ViewerProperties viewerProperties;
  private final DiscoveryClient discoveryClient;
  private final AtomicInteger infiniteCounter = new AtomicInteger();

  public RemoteThumbnailManager(
      final ViewerProperties viewerProperties, final DiscoveryClient discoveryClient) {
    this.viewerProperties = viewerProperties;
    this.discoveryClient = discoveryClient;
    webClient = WebClient.create();
    refreshServiceDiscovery();
  }

  @Scheduled(fixedDelay = 60 * 1000)
  private void refreshServiceDiscovery() {
    final List<ServiceInstance> serviceInstances =
        discoveryClient.getInstances(viewerProperties.getThumbnailerService());
    final Instant now = Instant.now();
    blockUntil.values().removeIf(now::isAfter);
    Flux.fromIterable(serviceInstances)
        .flatMap(
            serviceInstance -> {
              final URI uri = serviceInstance.getUri();

              return webClient
                  .get()
                  .uri(uri.resolve("mediatypes"))
                  .accept(MediaType.APPLICATION_JSON)
                  .exchange()
                  .flatMap(r -> r.bodyToMono(String[].class))
                  .flatMapIterable(Arrays::asList)
                  .map(MediaType::parseMediaType)
                  .map(t -> Tuples.of(t, uri))
                  .onErrorResume(
                      ex -> {
                        log.warn(
                            "Cannot call " + serviceInstance.getInstanceId() + " (" + uri + ")",
                            ex);
                        return Mono.empty();
                      });
            })
        .publish(
            in ->
                Mono.zip(
                    in.map(Tuple2::getT2).collect(Collectors.toSet()),
                    in.groupBy(Tuple2::getT1)
                        .flatMap(
                            f -> f.map(Tuple2::getT2).collectList().map(l -> Tuples.of(f.key(), l)))
                        .collectMap(Tuple2::getT1, Tuple2::getT2)))
        .map(t -> new CurrentEndpointData(t.getT2(), t.getT1()))
        .single()
        .subscribe(
            endpointData::set,
            ex -> {
              log.error("Cannot collect thumbnailers information", ex);
            });
  }

  @Override
  public Mono<File> takeThumbnail(
      final ObjectId id, final ObjectLoader objectLoader, MediaType mediaType) {
    return selectAvailableEndpoint(mediaType)
        .flatMap(
            uri ->
                webClient
                    .get()
                    .uri(uri.resolve("token"))
                    .exchange()
                    .flatMap(
                        tokenClientResponse -> {
                          // log.info("Token take response: " + tokenClientResponse.statusCode());
                          if (tokenClientResponse.statusCode().value() != 200) {
                            log.warn("Invalid token response: " + tokenClientResponse.statusCode());
                            blockUntil.put(uri, Instant.now().plusSeconds(1));
                            return takeThumbnail(id, objectLoader, mediaType);
                          } else {
                            return tokenClientResponse
                                .bodyToMono(String.class)
                                .map(Optional::of)
                                .defaultIfEmpty(Optional.empty())
                                // .log("Token")
                                .flatMap(
                                    token -> {
                                      if (token.isEmpty()) {
                                        blockUntil.put(uri, Instant.now().plusSeconds(1));
                                        return takeThumbnail(id, objectLoader, mediaType);

                                      } else
                                        return webClient
                                            .post()
                                            .uri(uri.resolve("thumbnail?token=" + token.get()))
                                            .contentType(mediaType)
                                            .contentLength(objectLoader.getSize())
                                            .accept(MediaType.IMAGE_JPEG)
                                            .body(
                                                BodyInserters.fromResource(
                                                    createResourceFromObjectLoader(
                                                        id, objectLoader, mediaType)))
                                            .exchange()
                                            .flatMap(
                                                clientResponse -> {
                                                  final HttpStatus statusCode =
                                                      clientResponse.statusCode();
                                                  // ölog.info("Status: " + statusCode);
                                                  if (statusCode == HttpStatus.OK)
                                                    return clientResponse
                                                        .body(BodyExtractors.toDataBuffers())
                                                        .collect(
                                                            () -> {
                                                              try {
                                                                return File.createTempFile(
                                                                    id.name(), ".tmp");
                                                              } catch (IOException e) {
                                                                throw new RuntimeException(
                                                                    "Cannot create temp file", e);
                                                              }
                                                            },
                                                            (file, dataBuffer) -> {
                                                              try (final FileOutputStream
                                                                  outputStream =
                                                                      new FileOutputStream(
                                                                          file, true)) {
                                                                @Cleanup
                                                                final InputStream input =
                                                                    dataBuffer.asInputStream(true);
                                                                IOUtils.copy(input, outputStream);
                                                              } catch (IOException e) {
                                                                throw new RuntimeException(
                                                                    "Cannot write response to file",
                                                                    e);
                                                              }
                                                            })
                                                        .doFinally(
                                                            signalType -> blockUntil.remove(uri));
                                                  else if (statusCode
                                                      == HttpStatus.NOT_ACCEPTABLE) {
                                                    blockUntil.put(
                                                        uri, Instant.now().plusSeconds(1));
                                                    return takeThumbnail(
                                                        id, objectLoader, mediaType);
                                                  } else {
                                                    return Mono.empty();
                                                    // return Mono.error(
                                                    //    new IOException("Unexpected Http response:
                                                    // " +
                                                    // statusCode));
                                                  }
                                                });
                                    });
                          }
                        }));
  }

  private Resource createResourceFromObjectLoader(
      final ObjectId id, final ObjectLoader objectLoader, final MediaType mediaType) {
    return new Resource() {

      @Override
      public InputStream getInputStream() throws IOException {
        return objectLoader.openStream();
      }

      @Override
      public boolean exists() {
        return true;
      }

      @Override
      public URL getURL() throws IOException {
        throw new IOException("URL not defined");
      }

      @Override
      public URI getURI() throws IOException {
        throw new IOException("URL not defined");
      }

      @Override
      public File getFile() throws IOException {
        throw new FileNotFoundException();
      }

      @Override
      public long contentLength() throws IOException {
        return objectLoader.getSize();
      }

      @Override
      public long lastModified() throws IOException {
        return System.currentTimeMillis();
      }

      @Override
      public Resource createRelative(final String relativePath) throws IOException {
        throw new IOException();
      }

      @Override
      public String getFilename() {
        if (mediaType.equals(MediaType.IMAGE_JPEG)) {
          return id.toString() + ".jpg";
        }
        return null;
      }

      @Override
      public String getDescription() {
        return id.toString();
      }
    };
  }

  private Mono<URI> selectAvailableEndpoint(final MediaType mediaType) {
    return Flux.fromIterable(
            endpointData.get().getClientsByType().getOrDefault(mediaType, Collections.emptyList()))
        .publish(
            in -> {
              final Instant now = Instant.now();
              ;
              return Mono.zip(
                  in.filter(
                          uri -> {
                            final Instant blockUntilTime = blockUntil.get(uri);
                            return blockUntilTime == null || blockUntilTime.isBefore(now);
                          })
                      .collectList(),
                  in.flatMap(uri -> Mono.justOrEmpty(blockUntil.get(uri)))
                      .collect(Collectors.minBy(Comparator.naturalOrder())));
            })
        .single()
        .flatMap(
            currentEndpointData -> {
              final List<URI> availableEndpoints = currentEndpointData.getT1();
              if (availableEndpoints.isEmpty()) {
                // no candidate found -> try it later
                return Mono.delay(
                        currentEndpointData
                            .getT2()
                            .map(until -> Duration.between(Instant.now(), until))
                            .map(d -> d.isNegative() ? Duration.ZERO : d)
                            .orElse(Duration.ofMillis(100).plusMillis(random.nextInt(20000))))
                    .flatMap(n -> selectAvailableEndpoint(mediaType));
              } else {
                // found one or multiple candidates -> select one
                return Mono.just(
                    availableEndpoints.get(
                        infiniteCounter.incrementAndGet() % availableEndpoints.size()));
              }
            });
  }

  @Value
  private static class CurrentEndpointData {
    private Map<MediaType, List<URI>> clientsByType;
    private Set<URI> knownEndpoints;
  }
}
