package ch.bergturbenthal.raoa.coordinator.service.impl;

import ch.bergturbenthal.raoa.coordinator.model.CoordinatorProperties;
import ch.bergturbenthal.raoa.coordinator.service.RemoteMediaProcessor;
import com.drew.lang.Charsets;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.ScalableResource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
public class KubernetesMediaProcessor implements RemoteMediaProcessor, Closeable {

  private final MixedOperation<Job, JobList, ScalableResource<Job>> jobs;
  private final String mediaProcessorTemplate;
  private final AtomicLong idCounter = new AtomicLong();
  private final Map<Long, MonoSink<Boolean>> waitingForCompletion =
      Collections.synchronizedMap(new HashMap<>());
  private final Scheduler scheduler;
  private Watch watch;

  public KubernetesMediaProcessor(
      final KubernetesClient kubernetesClient,
      final CoordinatorProperties properties,
      ScheduledExecutorService executorService) {
    jobs = kubernetesClient.batch().v1().jobs();
    mediaProcessorTemplate = properties.getMediaProcessorTemplate();
    final List<Job> initItems = jobs.list(createListOptions()).getItems();
    log.info("Found {} items in KubernetesMediaProcessor", initItems.size());
    for (final Job job : initItems) {
      final String name = job.getMetadata().getName();
      log.info("Delete old job {}", name);
      jobs.withName(name).delete();
    }
    scheduler = Schedulers.fromExecutor(executorService);

    Consumer<Job> jobStatusConsumer =
        resource -> {
          final ObjectMeta metadata = resource.getMetadata();
          final JobStatus status = resource.getStatus();
          final long jobId = Long.parseLong(metadata.getLabels().get("job-id"));
          final Integer failed = status.getFailed();
          if (failed != null && failed > 0) {
            log.info("Failed: " + status);
            final MonoSink<Boolean> waitingSink = waitingForCompletion.remove(jobId);
            if (waitingSink != null) {
              log.warn(
                  "Job Failed: " + jobs.withName(resource.getMetadata().getName()).getLog(true));
              waitingSink.success(false);
            }
            jobs.withName(resource.getMetadata().getName()).delete();
          }
          final Integer succeeded = status.getSucceeded();
          if (succeeded != null && succeeded > 0) {
            jobs.withName(resource.getMetadata().getName()).delete();
            final MonoSink<Boolean> waitingSink = waitingForCompletion.remove(jobId);
            if (waitingSink != null) waitingSink.success(true);
          }
        };

    executorService.scheduleWithFixedDelay(
        () -> {
          try {
            final List<Job> items = jobs.list(createListOptions()).getItems();
            items.forEach(jobStatusConsumer);
            startWatch(jobStatusConsumer, executorService);
          } catch (Exception ex) {
            log.warn("Error polling", ex);
          }
        },
        1,
        1,
        TimeUnit.MINUTES);

    startWatch(jobStatusConsumer, executorService);
  }

  @NotNull
  private ListOptions createListOptions() {
    final String label = "coordinator";
    final String value = "raoa-job-coordinator";
    return createListOptions(label, value);
  }

  @NotNull
  private ListOptions createListOptions(final String label, final String value) {
    final ListOptions listOptions = new ListOptions();
    listOptions.setLabelSelector(label + "=" + value);
    return listOptions;
  }

  private synchronized void startWatch(
      final Consumer<Job> jobStatusConsumer, final ScheduledExecutorService executorService) {
    // log.info("Start Watch");
    if (watch != null) watch.close();
    watch =
        jobs.watch(
            createListOptions(),
            new Watcher<>() {
              @Override
              public void eventReceived(final Action action, final Job resource) {
                try {
                  jobStatusConsumer.accept(resource);
                } catch (Exception ex) {
                  log.warn("Error processing watch update", ex);
                }
              }

              @Override
              public void onClose(final WatcherException cause) {
                if (cause != null) log.warn("Closed watch", cause);
              }
            });
  }

  @Override
  public Mono<Boolean> processFiles(
      final UUID album, final Collection<String> files, String additionalResource) {

    // log.info("Created " + createdJob);
    return Mono.<Boolean>create(
            sink -> {
              final String fileList =
                  files.stream()
                      .map(s -> URLEncoder.encode(s, Charsets.UTF_8))
                      .collect(Collectors.joining(","));
              final long jobId = idCounter.incrementAndGet();
              final String filledTemplate =
                  mediaProcessorTemplate
                      .replace("$repoId$", album.toString())
                      .replace("$fileList$", fileList)
                      .replace("$jobId$", String.valueOf(jobId))
                      .replace("$additionalResource$: $value$", additionalResource);
              final ScalableResource<Job> expandedJob =
                  jobs.load(
                      new ByteArrayInputStream(filledTemplate.getBytes(StandardCharsets.UTF_8)));
              /*
               * final UnaryOperator<Job> coordinator = (Job job) -> { log.info("jobRef: " + job);
               * job.getMetadata().setLabels( Map.of("coordinator", "raoa-jobRef-coordinator", "jobRef-id",
               * String.valueOf(jobId))); job.getMetadata().setName("raoa-jobRef-" + jobId); return job; };
               * expandedJob.edit(coordinator);
               */

              AtomicBoolean started = new AtomicBoolean(false);
              waitingForCompletion.put(jobId, sink);
              sink.onRequest(
                  count -> {
                    if (count > 0) {
                      if (started.compareAndSet(false, true)) {
                        expandedJob.create();
                      }
                    }
                  });

              sink.onCancel(
                  () -> {
                    final MonoSink<Boolean> removed = waitingForCompletion.remove(jobId);
                    expandedJob.delete();
                    if (removed != null) removed.success(false);
                  });
              sink.onDispose(
                  () -> {
                    final MonoSink<Boolean> remove = waitingForCompletion.remove(jobId);
                    if (remove != null) {
                      log.warn("Dispose without close " + jobId);
                      remove.success(false);
                    }
                  });
            })
        .publishOn(scheduler);
  }

  @Override
  public void close() {
    if (watch != null) watch.close();
  }
}
