package com.hotvect.python.direct;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public final class DirectWorkerManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DirectWorkerManager.class);
    private static final Duration GET_WORK_POLL_INTERVAL = Duration.ofMillis(200);

    private static final Tracer tracer =
            GlobalOpenTelemetry.getTracer(DirectWorkerManager.class.getName());

    private final DirectWorkersConfig config;
    private final String queueWaitSpanName;
    private final String workerRttSpanName;
    private final AtomicLong requestSeq = new AtomicLong(0L);

    private final ScheduledThreadPoolExecutor timeoutScheduler;
    private final Duration hungWorkerTimeout;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final BlockingQueue<QueuedRequest> workQueue;
    private final ConcurrentMap<String, QueuedRequest> pending;
    private final Set<CompletableFuture<Result>> retryOverallFutures = ConcurrentHashMap.newKeySet();
    private final List<DirectWorkerProcess> workers;

    public record Result(float[] output, String debugJson) {
    }

    public DirectWorkerManager(PythonWorkerCommand pythonWorkerCommand, DirectWorkersConfig config) {
        this(pythonWorkerCommand, config, "hotvect");
    }

    public DirectWorkerManager(PythonWorkerCommand pythonWorkerCommand, DirectWorkersConfig config, String spanNamePrefix) {
        this.config = Objects.requireNonNull(config, "config");
        String prefix = (spanNamePrefix == null || spanNamePrefix.isBlank()) ? "hotvect" : spanNamePrefix.trim();
        this.queueWaitSpanName = prefix + ".direct_worker.queue_wait";
        this.workerRttSpanName = prefix + ".direct_worker.worker_rtt";

        this.timeoutScheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "hv-direct-worker-timeouts");
            t.setDaemon(true);
            return t;
        });
        this.timeoutScheduler.setRemoveOnCancelPolicy(true);
        this.timeoutScheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.timeoutScheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        this.hungWorkerTimeout = config.requestTimeout().multipliedBy(2);

        this.pending = new ConcurrentHashMap<>();
        this.workQueue = new LinkedBlockingQueue<>(Math.toIntExact(config.totalQueueSize()));

        List<DirectWorkerProcess> started = new ArrayList<>(config.workerCount());
        try {
            for (int i = 0; i < config.workerCount(); i++) {
                started.add(new DirectWorkerProcess(i, pythonWorkerCommand, config, this));
            }
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                ExecutorCompletionService<Void> completion = new ExecutorCompletionService<>(executor);
                for (DirectWorkerProcess p : started) {
                    completion.submit(() -> {
                        p.start();
                        return null;
                    });
                }

                for (int i = 0; i < started.size(); i++) {
                    Future<Void> f = completion.take();
                    f.get();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
                throw new RuntimeException("Interrupted while starting direct workers", ie);
            } catch (ExecutionException ee) {
                executor.shutdownNow();
                Throwable cause = ee.getCause();
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException("Failed to start direct workers", cause);
            } finally {
                executor.shutdown();
            }
        } catch (Exception e) {
            for (DirectWorkerProcess p : started) {
                try {
                    p.close();
                } catch (Exception closeEx) {
                    log.debug("Failed to close direct worker during failed start", closeEx);
                }
            }
            timeoutScheduler.shutdownNow();
            throw new RuntimeException("Failed to start direct worker manager", e);
        }
        this.workers = List.copyOf(started);
    }

    public DirectWorkersConfig config() {
        return config;
    }

    Duration hungWorkerTimeout() {
        return hungWorkerTimeout;
    }

    boolean isClosed() {
        return closed.get();
    }

    ScheduledFuture<?> scheduleTimeout(Runnable task, Duration delay) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(delay, "delay");
        if (closed.get()) {
            throw new RejectedExecutionException("Direct worker manager is closed");
        }
        return timeoutScheduler.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
    }

    public static String formatWireRequestId(String requestId, long seq) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must be non-empty");
        }
        if (seq < 0) {
            throw new IllegalArgumentException("seq must be >= 0");
        }
        return requestId + "#" + Long.toString(seq, 36);
    }

    public CompletableFuture<Result> submit(String requestId, int batchSize, List<byte[]> payloads) {
        int maxAttempts = config.retryMaxAttempts();
        if (maxAttempts <= 1) {
            return submitOnce(requestId, batchSize, payloads);
        }

        CompletableFuture<Result> overall = new CompletableFuture<>();
        retryOverallFutures.add(overall);
        overall.whenComplete((ignored1, ignored2) -> retryOverallFutures.remove(overall));
        submitAttempt(requestId, batchSize, payloads, 1, maxAttempts, overall);
        return overall;
    }

    private void submitAttempt(
            String requestId,
            int batchSize,
            List<byte[]> payloads,
            int attempt,
            int maxAttempts,
            CompletableFuture<Result> overall
    ) {
        if (overall.isDone()) {
            return;
        }
        if (closed.get()) {
            overall.completeExceptionally(new RejectedExecutionException("Direct worker manager is closed"));
            return;
        }

        CompletableFuture<Result> attemptFuture;
        try {
            attemptFuture = submitOnce(requestId, batchSize, payloads);
        } catch (Exception e) {
            overall.completeExceptionally(e);
            return;
        }

        attemptFuture.whenComplete((result, error) -> {
            if (overall.isDone()) {
                return;
            }
            if (error == null) {
                overall.complete(result);
                return;
            }

            Throwable cause = unwrapCompletionException(error);
            if (attempt >= maxAttempts || !isRetryable(cause) || closed.get()) {
                overall.completeExceptionally(cause);
                return;
            }

            Duration backoff = config.retryBackoff();
            if (backoff != null && !backoff.isZero()) {
                try {
                    scheduleTimeout(
                            () -> submitAttempt(requestId, batchSize, payloads, attempt + 1, maxAttempts, overall),
                            backoff
                    );
                } catch (Exception schedulingError) {
                    overall.completeExceptionally(cause);
                }
            } else {
                submitAttempt(requestId, batchSize, payloads, attempt + 1, maxAttempts, overall);
            }
        });
    }

    private static Throwable unwrapCompletionException(Throwable error) {
        Throwable t = error;
        while (t instanceof java.util.concurrent.CompletionException || t instanceof ExecutionException) {
            if (t.getCause() == null) {
                break;
            }
            t = t.getCause();
        }
        return t;
    }

    private static boolean isRetryable(Throwable error) {
        if (error instanceof TimeoutException) {
            return true;
        }
        if (error instanceof DirectWorkerFailedException) {
            return true;
        }
        return false;
    }

    private CompletableFuture<Result> submitOnce(String requestId, int batchSize, List<byte[]> payloads) {
        if (closed.get()) {
            throw new IllegalStateException("DirectWorkerManager is closed");
        }
        Objects.requireNonNull(payloads, "payloads");

        Context parentContext = Context.current();
        Context otelContext = parentContext != null && Span.fromContext(parentContext).getSpanContext().isValid()
                ? parentContext
                : null;
        String wireRequestId = formatWireRequestId(requestId, requestSeq.getAndIncrement());
        byte[] workPayload;
        try {
            workPayload = DirectIpcProtocol.encodeWork(
                    wireRequestId,
                    batchSize,
                    payloads,
                    traceparentOrNull(parentContext)
            );
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }

        boolean callerBlocks = config.queueFullPolicy() == QueueFullPolicy.CALLER_BLOCKS;
        long deadlineNs = callerBlocks ? Long.MAX_VALUE : (System.nanoTime() + config.requestTimeout().toNanos());
        Span queueWaitSpan = startQueueWaitSpan(otelContext, batchSize);
        QueuedRequest qr = new QueuedRequest(wireRequestId, workPayload, batchSize, deadlineNs, otelContext, queueWaitSpan);
        if (pending.putIfAbsent(wireRequestId, qr) != null) {
            endRequestSpans(qr, new IllegalStateException("Duplicate in-flight requestId: " + wireRequestId));
            throw new IllegalStateException("Duplicate in-flight requestId: " + wireRequestId);
        }

        if (!callerBlocks) {
            try {
                qr.e2eTimeout = scheduleTimeout(
                        () -> failPending(wireRequestId, new TimeoutException("Direct worker request timed out (e2e): " + wireRequestId)),
                        config.requestTimeout()
                );
            } catch (RejectedExecutionException e) {
                failPending(wireRequestId, e);
                return qr.future;
            }
        }

        if (pending.get(wireRequestId) != qr) {
            // The request was failed concurrently (e.g. shutdown) before it could be enqueued.
            return qr.future;
        }

        boolean enqueued;
        try {
            enqueued = switch (config.queueFullPolicy()) {
                case REJECT -> workQueue.offer(qr);
                case CALLER_BLOCKS -> enqueueBlocking(qr);
            };
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            pending.remove(wireRequestId, qr);
            cancelTimeout(qr);
            endRequestSpans(qr, ie);
            throw new RuntimeException("Interrupted while submitting direct worker request", ie);
        }

        if (!enqueued) {
            if (callerBlocks) {
                if (!qr.future.isDone()) {
                    failPending(wireRequestId, new RejectedExecutionException("Direct worker manager is shutting down"));
                }
                return qr.future;
            }

            if (!pending.remove(wireRequestId, qr)) {
                return qr.future;
            }
            cancelTimeout(qr);
            endRequestSpans(qr, new RejectedExecutionException("Direct worker manager queue full"));
            throw new RejectedExecutionException("Direct worker manager queue full");
        }

        if (pending.get(wireRequestId) != qr) {
            // The request was failed concurrently (e.g. timeout) after being enqueued; avoid leaving zombie work.
            try {
                workQueue.remove(qr);
            } catch (Exception ignored) {
            }
        }

        return qr.future;
    }

    private boolean enqueueBlocking(QueuedRequest req) throws InterruptedException {
        while (true) {
            if (!pending.containsKey(req.wireRequestId)) {
                // Request has already timed out / been failed by another thread.
                return true;
            }
            if (closed.get()) {
                return false;
            }
            if (workQueue.offer(req, GET_WORK_POLL_INTERVAL.toNanos(), TimeUnit.NANOSECONDS)) {
                return true;
            }
        }
    }

    QueuedRequest waitForWorkOrNullWhenClosed(BooleanSupplier shouldAbort) throws InterruptedException {
        while (!closed.get() && (shouldAbort == null || !shouldAbort.getAsBoolean())) {
            QueuedRequest req = workQueue.poll(GET_WORK_POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
            if (req == null) {
                continue;
            }
            if (!pending.containsKey(req.wireRequestId)) {
                continue;
            }
            if (req.deadlineNs != Long.MAX_VALUE && System.nanoTime() > req.deadlineNs) {
                failPending(req.wireRequestId, new TimeoutException("Direct worker request timed out before dispatch: " + req.wireRequestId));
                continue;
            }
            return req;
        }
        return null;
    }

    boolean markDispatched(int workerIndex, QueuedRequest req) {
        if (pending.get(req.wireRequestId) != req) {
            endQueueWaitSpan(req, new TimeoutException("Direct worker request was removed before dispatch: " + req.wireRequestId));
            return false;
        }

        if (req.e2eTimeout == null) {
            try {
                req.e2eTimeout = scheduleTimeout(
                        () -> failPending(req.wireRequestId, new TimeoutException("Direct worker request timed out (e2e): " + req.wireRequestId)),
                        config.requestTimeout()
                );
            } catch (RejectedExecutionException e) {
                failPending(req.wireRequestId, e);
                return false;
            }
        }

        endQueueWaitSpan(req, null);

        if (req.otelContext == null) {
            return true;
        }

        Span rttSpan = tracer.spanBuilder(workerRttSpanName)
                .setParent(req.otelContext)
                .setAttribute("worker.index", workerIndex)
                .setAttribute("batch.size", req.batchSize)
                .startSpan();
        req.workerRttSpan = rttSpan;
        return true;
    }

    void completeResult(String wireRequestId, float[] output, String debugJson) {
        QueuedRequest req = pending.remove(wireRequestId);
        if (req == null) {
            log.debug("Late RESULT dropped for requestId={}", wireRequestId);
            return;
        }
        cancelTimeout(req);
        endRequestSpans(req, null);
        req.future.complete(new Result(output, debugJson));
    }

    void completeRequestError(String wireRequestId, String message) {
        QueuedRequest req = pending.remove(wireRequestId);
        if (req == null) {
            log.debug("Late REQUEST_ERROR dropped for requestId={}", wireRequestId);
            return;
        }
        cancelTimeout(req);
        RuntimeException e = new RuntimeException("Direct worker request error: " + message);
        endRequestSpans(req, e);
        req.future.completeExceptionally(e);
    }

    void failPending(String wireRequestId, Exception e) {
        QueuedRequest req = pending.remove(wireRequestId);
        if (req == null) {
            return;
        }
        try {
            workQueue.remove(req);
        } catch (Exception ignored) {
        }
        cancelTimeout(req);
        endRequestSpans(req, e);
        req.future.completeExceptionally(e);
    }

    private void failAllPending(Exception e) {
        List<String> ids = new ArrayList<>(pending.keySet());
        for (String id : ids) {
            failPending(id, e);
        }
    }

    private static void cancelTimeout(QueuedRequest req) {
        ScheduledFuture<?> t = req.e2eTimeout;
        if (t != null) {
            req.e2eTimeout = null;
            try {
                t.cancel(false);
            } catch (Exception ignored) {
            }
        }
    }

    private Span startQueueWaitSpan(Context parentContext, int batchSize) {
        if (parentContext == null) {
            return null;
        }
        if (!Span.fromContext(parentContext).getSpanContext().isValid()) {
            return null;
        }
        return tracer.spanBuilder(queueWaitSpanName)
                .setParent(parentContext)
                .setAttribute("batch.size", batchSize)
                .startSpan();
    }

    private static void endQueueWaitSpan(QueuedRequest req, Throwable errorOrNull) {
        Span span = req.queueWaitSpan;
        if (span == null) {
            return;
        }
        req.queueWaitSpan = null;
        if (errorOrNull != null) {
            try {
                span.recordException(errorOrNull);
                span.setStatus(StatusCode.ERROR);
            } catch (Exception ignored) {
            }
        }
        try {
            span.end();
        } catch (Exception ignored) {
        }
    }

    private static void endRequestSpans(QueuedRequest req, Throwable errorOrNull) {
        endQueueWaitSpan(req, errorOrNull);

        Span rttSpan = req.workerRttSpan;
        if (rttSpan != null) {
            req.workerRttSpan = null;
            if (errorOrNull != null) {
                try {
                    rttSpan.recordException(errorOrNull);
                    rttSpan.setStatus(StatusCode.ERROR);
                } catch (Exception ignored) {
                }
            }
            try {
                rttSpan.end();
            } catch (Exception ignored) {
            }
        }
    }

    private static String traceparentOrNull(Context context) {
        if (context == null) {
            return null;
        }
        SpanContext spanContext = Span.fromContext(context).getSpanContext();
        if (!spanContext.isValid()) {
            return null;
        }
        return "00-" + spanContext.getTraceId()
                + "-" + spanContext.getSpanId()
                + "-" + spanContext.getTraceFlags().asHex();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        RejectedExecutionException shuttingDown = new RejectedExecutionException("Direct worker manager is shutting down");
        for (CompletableFuture<Result> f : retryOverallFutures) {
            f.completeExceptionally(shuttingDown);
        }
        retryOverallFutures.clear();

        failAllPending(shuttingDown);
        workQueue.clear();

        for (DirectWorkerProcess w : workers) {
            try {
                w.close();
            } catch (Exception e) {
                log.warn("Failed to close direct worker", e);
            }
        }

        timeoutScheduler.shutdownNow();
    }

    static final class QueuedRequest {
        final String wireRequestId;
        final byte[] workPayload;
        final int batchSize;
        final long deadlineNs;
        final Context otelContext;
        volatile Span queueWaitSpan;
        volatile Span workerRttSpan;
        final CompletableFuture<Result> future;
        volatile ScheduledFuture<?> e2eTimeout;

        private QueuedRequest(
                String wireRequestId,
                byte[] workPayload,
                int batchSize,
                long deadlineNs,
                Context otelContext,
                Span queueWaitSpan
        ) {
            this.wireRequestId = Objects.requireNonNull(wireRequestId, "wireRequestId");
            this.workPayload = Objects.requireNonNull(workPayload, "workPayload");
            this.batchSize = batchSize;
            this.deadlineNs = deadlineNs;
            this.otelContext = otelContext;
            this.queueWaitSpan = queueWaitSpan;
            this.future = new CompletableFuture<>();
        }
    }
}
