package com.hotvect.onlineutils.experimentmanagement.experimentation;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Throwables;
import com.hotvect.onlineutils.experimentmanagement.models.ExperimentationState;
import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmRepository;
import com.hotvect.onlineutils.experimentmanagement.httpclient.ExperimentManagementServiceClient;
import it.unimi.dsi.fastutil.Pair;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-slot background refresher that fetches EMS state and converts it into serving state.
 * <p>
 * The refreshable serving snapshot is kept in {@link #experimentationManagerState}. The
 * shard bucket count is treated as slot configuration: it is initialized from the first
 * successful EMS payload and must remain unchanged for the lifetime of this updater.
 */
public class ExperimentStateUpdater extends AbstractScheduledService {
    private static final Logger LOG = LoggerFactory.getLogger(ExperimentStateUpdater.class);

    private final String slotName;
    private final AlgorithmRepository algorithmRepository;
    // This state shall be updated via a background thread
    private final AtomicReference<ExperimentationState> experimentationManagerState = new AtomicReference<>();
    private final Duration stateRefreshPeriod;
    private final AtomicReference<Pair<Instant, String>> lastFailure = new AtomicReference<>();
    private final AtomicReference<Instant> lastUpdated = new AtomicReference<>();
    private final ExperimentManagementServiceClient experimentManagementServiceClient;
    private final Object refreshLock = new Object();
    private volatile Integer totalNumberOfShards;

    public ExperimentStateUpdater(
            final String slotName,
            final AlgorithmRepository algorithmRepository,
            final Duration stateRefreshPeriod,
            final ExperimentManagementServiceClient experimentManagementServiceClient) {
        this.slotName = Objects.requireNonNull(slotName, "slotName must not be null");
        this.algorithmRepository = Objects.requireNonNull(algorithmRepository, "algorithmRepository must not be null");
        this.stateRefreshPeriod = Objects.requireNonNull(stateRefreshPeriod, "stateRefreshPeriod must not be null");
        checkState(!stateRefreshPeriod.isZero() && !stateRefreshPeriod.isNegative(),
                "stateRefreshPeriod must be positive");
        this.experimentManagementServiceClient = Objects.requireNonNull(
                experimentManagementServiceClient,
                "experimentManagementServiceClient must not be null");
    }

    @Override
    protected void runOneIteration() {
        updateState();
    }

    @Override
    protected Scheduler scheduler() {
        final long refreshPeriodMillis = stateRefreshPeriod.toMillis();
        checkState(refreshPeriodMillis > 0,
                "stateRefreshPeriod %s must be at least 1 millisecond",
                stateRefreshPeriod);
        return Scheduler.newFixedRateSchedule(
                refreshPeriodMillis,
                refreshPeriodMillis,
                TimeUnit.MILLISECONDS);
    }

    public void updateState() {
        try {
            refreshStateOrThrow();
        } catch (final Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("Throwable while updating {} for slot {}", this.getClass().getSimpleName(), slotName, e);
            // We don't want this background task to stop, so carry on
            // Issues will be detected via the staleness of experimentation state
            lastFailure.set(Pair.of(Instant.now(), Throwables.getStackTraceAsString(Throwables.getRootCause(e))));
        }
    }

    public void refreshStateOrThrow() throws Exception {
        synchronized (refreshLock) {
            final Slot slot = experimentManagementServiceClient.getDefaultVariantAndActiveExperiments(slotName);
            final int refreshedTotalNumberOfShards = slot.totalNumberOfShards();
            checkState(refreshedTotalNumberOfShards > 0,
                    "totalNumberOfShards can't be zero");
            initializeOrValidateTotalNumberOfShards(refreshedTotalNumberOfShards);

            final OnlineSlotToExperimentationStateConverter converter =
                    new OnlineSlotToExperimentationStateConverter(algorithmRepository);
            final ExperimentationState newState = converter.convert(slot);

            experimentationManagerState.set(newState);
            lastUpdated.set(Instant.now());
            lastFailure.set(null);
            LOG.info("Completed updating state for slot {}.", slotName);
        }
    }

    public ExperimentationState getCurrentState() {
        return experimentationManagerState.get();
    }

    public int getTotalNumberOfShards() {
        final Integer configuredTotalNumberOfShards = totalNumberOfShards;
        checkState(configuredTotalNumberOfShards != null,
                "No totalNumberOfShards loaded for slot %s", slotName);
        return configuredTotalNumberOfShards;
    }

    public Pair<Instant, String> getLastFailure() {
        return lastFailure.get();
    }

    public Instant getLastUpdated() {
        return lastUpdated.get();
    }

    private void initializeOrValidateTotalNumberOfShards(final int refreshedTotalNumberOfShards) {
        if (totalNumberOfShards == null) {
            totalNumberOfShards = refreshedTotalNumberOfShards;
            return;
        }

        checkState(
                totalNumberOfShards == refreshedTotalNumberOfShards,
                "totalNumberOfShards changed for slot %s from %s to %s. "
                        + "This value is treated as immutable slot configuration.",
                slotName,
                totalNumberOfShards,
                refreshedTotalNumberOfShards);
    }
}
