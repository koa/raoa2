package ch.bergturbenthal.raoa.libs;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;

@Slf4j
public class StreamObserverReactiveHelper {

  public static <T> Subscriber<T> toSubscriber(final StreamObserver<T> observer) {
    return toSubscriber(observer, Optional.empty());
  }

  public static <T> Subscriber<T> toSubscriber(
      final StreamObserver<T> observer, final Optional<String> logTag) {
    final Exception stackTraceHolder = new Exception();
    final AtomicReference<Runnable> cancelHandler = new AtomicReference<>(() -> {});
    logTag.ifPresent(t -> log.info("SOSUB {} create observer: {}", t, observer));
    if (observer instanceof ServerCallStreamObserver) {
      ((ServerCallStreamObserver<T>) observer)
          .setOnCancelHandler(
              () -> {
                logTag.ifPresent(t -> log.info("SOSUB {} cancelHandler", t));
                cancelHandler.get().run();
              });
    }
    return new Subscriber<T>() {

      private Subscription subscription;

      @Override
      public void onSubscribe(final Subscription s) {
        logTag.ifPresent(t -> log.info("SOSUB {} subscribe start {}", t, s));
        subscription = s;
        s.request(1);
        cancelHandler.set(s::cancel);
        logTag.ifPresent(t -> log.info("SOSUB {} subscribe end {}", t, s));
      }

      @Override
      public void onNext(final T v) {
        logTag.ifPresent(t -> log.info("SOSUB {} next: {}", t, v));
        observer.onNext(v);
        subscription.request(1);
      }

      @Override
      public void onError(final Throwable t) {
        logTag.ifPresent(tg -> log.info("SOSUB {} error", tg, t));

        if (log.isDebugEnabled()) {
          final StringBuilder sb =
              new StringBuilder().append("SOSUB ").append(logTag.orElse("")).append(" error ");
          appendOriginalStack(sb);
          log.debug(sb.toString(), t);
        }

        observer.onError(t);
      }

      @Override
      public void onComplete() {
        logTag.ifPresent(t -> log.info("SOSUB {} complete", t));
        observer.onCompleted();
      }

      private void appendOriginalStack(final StringBuilder sb) {
        final String thisName = StreamObserverReactiveHelper.class.getName();
        final StackTraceElement[] createStackTrace = stackTraceHolder.getStackTrace();
        buildStackTrace(sb, thisName, createStackTrace, subscription);
      }
    };
  }

  /** Use for i.E. swagger wrappers, NOT for remote targets */
  public static <ReqT, RespT> Publisher<RespT> createLocalPublisher(
      final ReqT request, final BiConsumer<ReqT, StreamObserver<RespT>> call) {
    return Flux.create(
        sink -> {
          call.accept(
              request,
              new StreamObserver<RespT>() {
                @Override
                public void onNext(final RespT value) {
                  sink.next(value);
                }

                @Override
                public void onError(final Throwable t) {
                  sink.error(t);
                }

                @Override
                public void onCompleted() {
                  sink.complete();
                }
              });
        });
  }

  public static <ReqT, RespT> Publisher<RespT> createPublisher(
      final ReqT request, final BiConsumer<ReqT, StreamObserver<RespT>> call) {
    // return createPublisher(request, call, Optional.of("["+ UUID.randomUUID()+"]"));
    return createPublisher(request, call, Optional.empty());
  }

  public static <ReqT, RespT> Publisher<RespT> createPublisher(
      final ReqT request,
      final BiConsumer<ReqT, StreamObserver<RespT>> call,
      final Optional<String> logTag) {
    @SuppressWarnings("ThrowableNotThrown")
    final Exception stackTraceHolder = new Exception();
    logTag.ifPresent(t -> log.info("SOPUB {} create", t));
    return subscriber -> {
      logTag.ifPresent(t -> log.info("SOPUB {} subscribe", t));
      AtomicReference<RespT> pendingEntry = new AtomicReference<>();
      Semaphore remainingContingent = new Semaphore(0);
      AtomicInteger pendingRequest = new AtomicInteger(0);
      AtomicReference<Runnable> performPendingUpdates = new AtomicReference<>();
      AtomicBoolean ready = new AtomicBoolean(false);
      AtomicBoolean cancelled = new AtomicBoolean(false);
      AtomicBoolean completed = new AtomicBoolean(false);
      AtomicBoolean delayedComplete = new AtomicBoolean(false);
      Context.ROOT.run(
          () ->
              call.accept(
                  request,
                  new ClientResponseObserver<ReqT, RespT>() {
                    private void handlePendingUpdates() {
                      if (ready.get()) {
                        final Runnable onReadyFn = performPendingUpdates.getAndSet(null);
                        if (onReadyFn != null) {
                          onReadyFn.run();
                        }
                      }
                    }

                    @Override
                    public void onNext(final RespT value) {
                      ready.set(true);
                      handlePendingUpdates();
                      if (remainingContingent.tryAcquire()) {
                        logTag.ifPresent(t -> log.info("SOPUB {} next: {}", t, value));

                        // log.info("return " + value + " remaining: " +
                        // remainingContingent.availablePermits());
                        subscriber.onNext(value);
                      } else {
                        logTag.ifPresent(t -> log.info("SOPUB {} pendingNext: {}", t, value));

                        // log.info("parking response " + value + " remaining: " +
                        // remainingContingent.availablePermits());
                        if (!pendingEntry.compareAndSet(null, value))
                          log.error(
                              "SOPUB {} Entry already pending, data lost: developer needs to create a full queue instead of single buffer",
                              logTag.orElse(""));
                      }
                    }

                    @Override
                    public void onError(final Throwable t) {
                      logTag.ifPresent(tg -> log.info("SOPUB {} error: ", tg, t));
                      if (cancelled.get()
                          && t instanceof StatusRuntimeException
                          && ((StatusRuntimeException) t).getStatus().getCode()
                              == Status.Code.CANCELLED) {
                        // ignore cancelled exception in cause of cancelled stream
                        return;
                      }
                      if (cancelled.get()
                          && t instanceof StatusException
                          && ((StatusException) t).getStatus().getCode() == Status.Code.CANCELLED) {
                        // ignore cancelled exception in cause of cancelled stream
                        return;
                      }
                      final StringBuilder sb =
                          new StringBuilder()
                              .append("SOPUB ")
                              .append(logTag.orElse(""))
                              .append(" error processing request ");
                      appendOriginalStack(sb);
                      if (t instanceof StatusRuntimeException) {
                        final StatusRuntimeException sre = (StatusRuntimeException) t;
                        subscriber.onError(
                            sre.getStatus()
                                .withCause(t)
                                .withDescription(sb.toString())
                                .asRuntimeException(sre.getTrailers()));
                      } else if (t instanceof StatusException) {
                        final StatusException sre = (StatusException) t;
                        subscriber.onError(
                            sre.getStatus()
                                .withCause(t)
                                .withDescription(sb.toString())
                                .asException(sre.getTrailers()));
                      } else {
                        subscriber.onError(new RuntimeException(sb.toString(), t));
                      }
                    }

                    private void appendOriginalStack(final StringBuilder sb) {
                      final String thisName = StreamObserverReactiveHelper.class.getName();
                      final StackTraceElement[] createStackTrace = stackTraceHolder.getStackTrace();
                      buildStackTrace(sb, thisName, createStackTrace, request);
                    }

                    @Override
                    public void onCompleted() {
                      if (!completed.compareAndSet(false, true)) {
                        final StringBuilder sb =
                            new StringBuilder("SOPUB ")
                                .append(logTag.orElse(""))
                                .append(" Illegal complete in publisher called from");
                        appendOriginalStack(sb);
                        log.error(sb.toString(), new IllegalStateException("already completed"));
                        return;
                      }
                      if (pendingEntry.get() != null) {
                        log.debug(
                            "SOPUB {} Completing with pending entry, delaying complete signal relay",
                            logTag.orElse(""));
                        delayedComplete.set(true);
                        return;
                      }
                      logTag.ifPresent(t -> log.info("SOPUB {} complete", t));
                      subscriber.onComplete();
                    }

                    @Override
                    public void beforeStart(final ClientCallStreamObserver<ReqT> requestStream) {
                      requestStream.disableAutoInboundFlowControl();

                      logTag.ifPresent(
                          t -> log.info("SOPUB {} beforeStart stream: {}", t, requestStream));
                      final Runnable pendingUpdateRunnable =
                          () -> {
                            logTag.ifPresent(
                                t ->
                                    log.info(
                                        "SOPUB {} performPendingUpdates: r:{}, pr:{}, pe:{}, rc:{}",
                                        t,
                                        ready.get(),
                                        pendingRequest.get(),
                                        pendingEntry.get(),
                                        remainingContingent.availablePermits()));
                            if (ready.get()) {
                              final int requestCount = pendingRequest.getAndSet(0);
                              if (requestCount > 0) {
                                final RespT pendingValue = pendingEntry.getAndSet(null);
                                if (pendingValue != null) {
                                  subscriber.onNext(pendingValue);
                                  remainingContingent.release(requestCount - 1);
                                  if (delayedComplete.compareAndSet(true, false)) {
                                    subscriber.onComplete();
                                  } else {
                                    if (requestCount > 1) requestStream.request(requestCount - 1);
                                  }
                                } else {
                                  remainingContingent.release(requestCount);
                                  requestStream.request(requestCount);
                                }
                              }
                            }
                          };
                      performPendingUpdates.set(pendingUpdateRunnable);
                      logTag.ifPresent(t -> log.info("SOPUB {} beforeStart after setOnReady", t));
                      subscriber.onSubscribe(
                          new Subscription() {
                            @Override
                            public void request(final long n) {
                              final int count = n > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) n;
                              logTag.ifPresent(
                                  t ->
                                      log.info(
                                          "SOPUB {} request: c:{}, r:{}, pr:{}, pe:{}, rc:{}",
                                          t,
                                          n,
                                          ready.get(),
                                          pendingRequest.get(),
                                          pendingEntry.get(),
                                          remainingContingent.availablePermits()));
                              if (count <= 0) return;
                              pendingRequest.addAndGet(count);
                              performPendingUpdates.set(pendingUpdateRunnable);
                              handlePendingUpdates();
                            }

                            @Override
                            public void cancel() {
                              logTag.ifPresent(t -> log.info("SOPUB {} cancel", t));
                              cancelled.set(true);
                              requestStream.cancel(
                                  "Subscription canceled", Status.CANCELLED.getCause());
                            }
                          });
                    }
                  }));
      logTag.ifPresent(t -> log.info("SOPUB {} accepted", t));
    };
  }

  private static <ReqT> void buildStackTrace(
      final StringBuilder sb,
      final String thisName,
      final StackTraceElement[] createStackTrace,
      final ReqT request) {
    if (createStackTrace != null && createStackTrace.length > 0) {
      for (int s = 0; s < createStackTrace.length; s++) {
        StackTraceElement se = createStackTrace[s];
        if (thisName.equals(Optional.ofNullable(se.getClassName()).orElse(""))
            && "createPublisher".equals(Optional.ofNullable(se.getMethodName()).orElse(""))) {
          sb.append("\nCalled by: ").append(request);
          int context = 7;
          boolean skipped = false;
          for (int i = s + 1; i < s + context && i < createStackTrace.length; i++) {
            final StackTraceElement element = createStackTrace[i];
            final String ecn = element.getClassName();
            if (ecn.equals(thisName)
                || ecn.startsWith(thisName + "$")
                || ecn.startsWith("reactor.core")) {
              // skip api noise
              context++;
              skipped = true;
              continue;
            }
            if (skipped) {
              skipped = false;
              sb.append("\n         ...");
            }
            sb.append("\n         at ").append(element.toString());
          }
          if (s + context < createStackTrace.length) {
            sb.append("\n         +").append(createStackTrace.length - s - context).append(" more");
          }
          sb.append("\n----------------------------");
        }
      }
    }
  }
}
