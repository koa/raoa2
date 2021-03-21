package ch.bergturbenthal.raoa.coordinator.resolver;

import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.Status;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

@Slf4j
public class ServiceDiscoveryNameResolver extends NameResolver {
  private final DiscoveryClient discoveryClient;
  private final String name;
  private final ScheduledExecutorService executorService;
  private ScheduledFuture<?> scheduledFuture;
  private Listener2 listener;

  public ServiceDiscoveryNameResolver(
      final DiscoveryClient discoveryClient,
      String name,
      ScheduledExecutorService executorService) {
    this.discoveryClient = discoveryClient;
    this.name = name;
    this.executorService = executorService;
  }

  @Override
  public String getServiceAuthority() {
    return name;
  }

  @Override
  public synchronized void start(final Listener2 listener) {
    this.listener = listener;
    if (scheduledFuture != null) scheduledFuture.cancel(true);
    scheduledFuture =
        executorService.scheduleWithFixedDelay(this::refresh, 0, 30, TimeUnit.SECONDS);
  }

  @Override
  public synchronized void shutdown() {
    if (scheduledFuture != null) scheduledFuture.cancel(true);
  }

  @Override
  public synchronized void refresh() {
    log.info("refresh: " + (listener != null));
    if (this.listener == null) return;
    try {
      final List<ServiceInstance> discoveryClientInstances = discoveryClient.getInstances(name);
      log.info("Found endpoints: " + discoveryClientInstances);
      final List<EquivalentAddressGroup> resolvedEndpoints =
          discoveryClientInstances.stream()
              .map(
                  instance ->
                      new EquivalentAddressGroup(
                          new InetSocketAddress(instance.getHost(), instance.getPort())))
              .collect(Collectors.toList());
      log.info("Result: " + resolvedEndpoints);
      this.listener.onResult(ResolutionResult.newBuilder().setAddresses(resolvedEndpoints).build());
    } catch (Throwable ex) {
      log.warn("Cannot resolve " + name, ex);
      this.listener.onError(Status.INTERNAL);
    }
  }
}
