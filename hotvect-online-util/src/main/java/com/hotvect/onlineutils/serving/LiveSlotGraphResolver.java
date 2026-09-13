package com.hotvect.onlineutils.serving;

import com.google.common.util.concurrent.AbstractScheduledService;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.onlineutils.experimentmanagement.httpclient.ExperimentManagementServiceClient;
import com.hotvect.onlineutils.util.Closeables;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime-wide live EMS graph publication.
 *
 * <p>Each cycle prepares a complete snapshot through {@link ServingSnapshotPreparer} and then
 * atomically publishes it. EMS reads remain independent; this class provides local atomic
 * activation, not an EMS transaction.</p>
 */
final class LiveSlotGraphResolver extends AbstractScheduledService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(LiveSlotGraphResolver.class);

    private final Map<String, ServingSlot> rootSlots;
    private final ServingSnapshotPreparer snapshotPreparer;
    private final Duration refreshPeriod;
    private final AtomicReference<RetainedResource<ServingSnapshot>> current = new AtomicReference<>();
    private final AtomicReference<ServingRefreshFailure> lastRefreshFailure = new AtomicReference<>();
    private final AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final Set<CompletableFuture<Void>> pendingRetirements = ConcurrentHashMap.newKeySet();

    LiveSlotGraphResolver(
            Map<String, ServingSlot> rootSlots,
            AlgorithmRepository algorithmRepository,
            ExperimentManagementServiceClient emsClient,
            Duration refreshPeriod) {
        this.refreshPeriod = requireSchedulableRefreshPeriod(refreshPeriod);
        TreeMap<String, ServingSlot> resolvedRootSlots = new TreeMap<>();
        Objects.requireNonNull(rootSlots, "rootSlots must not be null").forEach((name, slot) -> {
            ServingSlot resolvedSlot = Objects.requireNonNull(slot, "rootSlot must not be null");
            if (!resolvedSlot.name().equals(name)) {
                throw new IllegalArgumentException("Root slot key must match slot name: " + name);
            }
            resolvedRootSlots.put(name, resolvedSlot);
        });
        if (resolvedRootSlots.isEmpty()) {
            throw new IllegalArgumentException("At least one root slot is required");
        }
        this.rootSlots = Map.copyOf(resolvedRootSlots);
        this.snapshotPreparer = new ServingSnapshotPreparer(
                this.rootSlots.keySet(),
                algorithmRepository,
                emsClient,
                (slotName, algorithmInstance) ->
                        this.rootSlots.get(slotName).validate(algorithmInstance));
    }

    /** Invokes one prepared root composition from a single immutable serving generation. */
    <ALGORITHM extends Algorithm, RESULT> AlgorithmExecution<RESULT> invoke(
            ServingSlot rootSlot,
            String assignmentKey,
            Function<? super ALGORITHM, ? extends RESULT> invocation) {
        try (RetainedResource.Lease<ServingSnapshot> lease = acquireSnapshot()) {
            ServingSnapshot snapshot = lease.resource();
            PreparedComposition selected = snapshot.select(rootSlot.name(), assignmentKey);
            AlgorithmRepository.CachedAlgorithmGraph rootGraph = selected.rootGraph();
            AlgorithmSelection selection = AlgorithmSelection.from(rootSlot.name(), selected);
            @SuppressWarnings("unchecked")
            ALGORITHM algorithm = (ALGORITHM) rootGraph.instance().algorithm();
            RESULT result = invocation.apply(algorithm);
            return new AlgorithmExecution<>(result, selection);
        }
    }

    ServingRuntimeStatus status() {
        RetainedResource<ServingSnapshot> observed = current.get();
        ServingSnapshot snapshot = observed == null ? null : observed.resource();
        ServingRefreshFailure failure = lastRefreshFailure.get();
        if (snapshot == null) {
            return new ServingRuntimeStatus(false, 0, null, null, Map.of(), failure);
        }
        return new ServingRuntimeStatus(
                true,
                snapshot.generation(),
                snapshot.activatedAt(),
                snapshot.preparationDuration(),
                snapshot.assignments(),
                failure);
    }

    void refreshNow() throws Exception {
        refreshSnapshot();
    }

    @Override
    protected void startUp() throws Exception {
        refreshSnapshot();
    }

    @Override
    protected void runOneIteration() {
        try {
            refreshSnapshot();
        } catch (Exception error) {
            LOG.error("Rejected complete live serving snapshot", error);
        }
    }

    @Override
    protected Scheduler scheduler() {
        long periodMillis = refreshPeriod.toMillis();
        return Scheduler.newFixedRateSchedule(periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    static Duration requireSchedulableRefreshPeriod(Duration refreshPeriod) {
        Duration resolved = Objects.requireNonNull(refreshPeriod, "refreshPeriod must not be null");
        if (resolved.toMillis() < 1) {
            throw new IllegalArgumentException("refreshPeriod must be at least one millisecond");
        }
        return resolved;
    }

    @Override
    protected void shutDown() throws Exception {
        closePublication();
    }

    @Override
    public void close() throws Exception {
        stopRefreshing();
        Closeables.closeAll(
                "Failed to close live serving runtime",
                this::closePublication,
                () -> {
                    State observed = state();
                    if (observed != State.NEW && observed != State.FAILED && observed != State.TERMINATED) {
                        awaitTerminated();
                    }
                },
                this::awaitRetirements);
    }

    private void refreshSnapshot() throws Exception {
        if (!refreshLock.tryLock()) {
            throw new IllegalStateException("A live serving graph refresh is already in progress");
        }
        try {
            requireOpen();
            long startedAtNanos = System.nanoTime();
            ServingSnapshot prepared;
            try {
                prepared = snapshotPreparer.prepareSnapshot(
                        ExecutionContext.realtime(InputSemantic.ONLINE));
            } catch (Throwable failure) {
                recordFailure(new ServingRefreshFailure(Instant.now(), failureSummary(failure)));
                rethrow(failure);
                throw new AssertionError("unreachable");
            }
            RetainedResource<ServingSnapshot> observed = current.get();
            ServingSnapshot previous = observed == null ? null : observed.resource();
            RetainedResource<ServingSnapshot> next;
            try {
                ServingSnapshot activated = prepared.activate(
                        previous == null ? 1 : previous.generation() + 1,
                        Instant.now(),
                        Duration.ofNanos(System.nanoTime() - startedAtNanos));
                next = retain(activated);
            } catch (RuntimeException | Error failure) {
                Closeables.closeAfterFailure(failure, prepared);
                throw failure;
            }
            if (closed.get()) {
                next.release();
                rejectAfterShutdown();
            }
            RetainedResource<ServingSnapshot> retired = current.getAndSet(next);
            if (closed.get()) {
                if (current.compareAndSet(next, null)) {
                    next.release();
                }
                if (retired != null) {
                    retired.release();
                }
                rejectAfterShutdown();
            }
            lastRefreshFailure.set(null);
            if (retired != null) {
                retired.release();
            }
            LOG.info(
                    "Activated live serving generation {} for {} configured roots",
                    next.resource().generation(),
                    rootSlots.size());
        } finally {
            refreshLock.unlock();
        }
    }

    private void closePublication() {
        closed.set(true);
        RetainedResource<ServingSnapshot> active = current.getAndSet(null);
        try {
            if (active != null) {
                active.release();
            }
        } finally {
            refreshLock.lock();
            try {
                // Await any in-flight refresh to finish and release.
            } finally {
                refreshLock.unlock();
            }
        }
    }

    private void awaitRetirements() throws Exception {
        // No more generations can be registered after closePublication has joined preparation.
        CompletableFuture.allOf(pendingRetirements.toArray(CompletableFuture[]::new))
                .handle((ignored, failure) -> null)
                .join();
        // Cleanup reports its cause before completing its future, including generations already removed
        // from the pending set. Surface that original cause after all generations have finished closing.
        Throwable failure = terminalFailure.get();
        if (failure != null) {
            rethrow(failure);
        }
    }

    private static void rejectAfterShutdown() {
        throw new IllegalStateException("Live serving graph resolver is closed");
    }

    private void recordFailure(ServingRefreshFailure failure) {
        if (!closed.get()) {
            lastRefreshFailure.set(failure);
        }
    }

    private RetainedResource.Lease<ServingSnapshot> acquireSnapshot() {
        while (true) {
            RetainedResource<ServingSnapshot> observed = current.get();
            if (observed == null) {
                Throwable failure = terminalFailure.get();
                if (failure != null) {
                    throw new IllegalStateException("Serving runtime failed", failure);
                }
                throw new IllegalStateException("Serving runtime is not ready or is closed");
            }
            RetainedResource.Lease<ServingSnapshot> lease = observed.tryAcquire();
            if (lease != null) {
                return lease;
            }
        }
    }

    private void requireOpen() {
        if (!closed.get()) {
            return;
        }
        throw new IllegalStateException("Live serving graph resolver is closed");
    }

    private RetainedResource<ServingSnapshot> retain(ServingSnapshot snapshot) {
        RetainedResource<ServingSnapshot> retained = new RetainedResource<>(
                snapshot,
                "live serving generation " + snapshot.generation(),
                "hotvect-serving-generation-" + snapshot.generation() + "-cleanup",
                this::failRuntime);
        CompletableFuture<Void> completion = retained.whenClosed();
        pendingRetirements.add(completion);
        completion.whenComplete((ignored, failure) -> pendingRetirements.remove(completion));
        return retained;
    }

    private void failRuntime(Throwable failure) {
        if (!terminalFailure.compareAndSet(
                null,
                Objects.requireNonNull(failure, "failure must not be null"))) {
            return;
        }
        closed.set(true);
        RetainedResource<ServingSnapshot> active = current.getAndSet(null);
        stopRefreshing();
        if (active != null) {
            active.release();
        }
    }

    private void stopRefreshing() {
        State observed = state();
        if (observed == State.STARTING || observed == State.RUNNING) {
            stopAsync();
        }
    }

    private static String failureSummary(Throwable failure) {
        if (failure instanceof java.io.IOException) {
            return "EMS read failed";
        }
        if (failure instanceof IllegalArgumentException || failure instanceof IllegalStateException) {
            return "Snapshot validation failed";
        }
        return "Snapshot preparation failed";
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new RuntimeException(failure);
    }

}
