package com.hotvect.onlineutils.experimentmanagement.experimentation;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.Service;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmRepository;
import com.hotvect.onlineutils.experimentmanagement.httpclient.ExperimentManagementServiceClient;
import com.hotvect.onlineutils.experimentmanagement.models.ExperimentationState;
import com.hotvect.onlineutils.experimentmanagement.models.VariantConfiguration;
import com.hotvect.onlineutils.experimentmanagement.variantassignment.VariantAssigner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Default lifecycle-managed implementation of {@link ExperimentationManager}.
 * <p>
 * A single EMS client is shared across all configured slots, while each slot keeps its
 * own refresher and immutable serving snapshot.
 */
public class DefaultExperimentationManager extends AbstractIdleService
        implements ExperimentationManager {
    private final ImmutableMap<String, ExperimentStateUpdater> stateUpdaters;

    /**
     * Creates a manager for the given slots.
     * <p>
     * Startup performs an initial refresh for every slot before the manager reaches
     * {@link State#RUNNING}.
     */
    public DefaultExperimentationManager(
            final AlgorithmRepository algorithmRepository,
            final Duration stateRefreshPeriod,
            final ExperimentManagementServiceClient experimentManagementServiceClient,
            final Collection<String> slotNames) {
        this(buildStateUpdaters(algorithmRepository, stateRefreshPeriod, experimentManagementServiceClient, slotNames));
    }

    DefaultExperimentationManager(final Map<String, ExperimentStateUpdater> stateUpdaters) {
        Objects.requireNonNull(stateUpdaters, "stateUpdaters must not be null");
        checkArgument(!stateUpdaters.isEmpty(), "stateUpdaters must not be empty");
        this.stateUpdaters = ImmutableMap.copyOf(stateUpdaters);
    }

    @Override
    public Set<String> slotNames() {
        return stateUpdaters.keySet();
    }

    @Override
    public VariantConfiguration assignVariant(final String slotName, final String customerNumber) {
        final ExperimentStateUpdater stateUpdater = getStateUpdater(slotName);
        final ExperimentationState currentState = requireCurrentState(slotName, stateUpdater);
        return VariantAssigner.assignVariant(customerNumber, currentState, stateUpdater.getTotalNumberOfShards());
    }

    @Override
    public ExperimentationState currentState(final String slotName) {
        return requireCurrentState(slotName, getStateUpdater(slotName));
    }

    @Override
    public int totalNumberOfShards(final String slotName) {
        final ExperimentStateUpdater stateUpdater = getStateUpdater(slotName);
        requireCurrentState(slotName, stateUpdater);
        return stateUpdater.getTotalNumberOfShards();
    }

    @Override
    public void refreshNow(final String slotName) {
        try {
            getStateUpdater(slotName).refreshStateOrThrow();
        } catch (final Exception e) {
            throw new RuntimeException("Failed to refresh experimentation state for slot " + slotName, e);
        }
    }

    @Override
    public void refreshAllNow() {
        for (final String slotName : stateUpdaters.keySet()) {
            refreshNow(slotName);
        }
    }

    @Override
    protected void startUp() throws Exception {
        final List<ExperimentStateUpdater> startedUpdaters = new ArrayList<>();
        try {
            for (final ExperimentStateUpdater stateUpdater : stateUpdaters.values()) {
                stateUpdater.refreshStateOrThrow();
                stateUpdater.startAsync().awaitRunning();
                startedUpdaters.add(stateUpdater);
            }
        } catch (final Exception e) {
            final RuntimeException stopFailure = stopUpdaters(startedUpdaters);
            if (stopFailure != null) {
                e.addSuppressed(stopFailure);
            }
            throw e;
        }
    }

    @Override
    protected void shutDown() throws Exception {
        final RuntimeException stopFailure = stopUpdaters(stateUpdaters.values());
        if (stopFailure != null) {
            throw stopFailure;
        }
    }

    @Override
    public void close() {
        stopAsync().awaitTerminated();
    }

    private ExperimentStateUpdater getStateUpdater(final String slotName) {
        final ExperimentStateUpdater stateUpdater = stateUpdaters.get(slotName);
        checkArgument(stateUpdater != null,
                "Unknown slot %s. Configured slots: %s",
                slotName,
                stateUpdaters.keySet());
        return stateUpdater;
    }

    private ExperimentationState requireCurrentState(
            final String slotName,
            final ExperimentStateUpdater stateUpdater) {
        final ExperimentationState currentState = stateUpdater.getCurrentState();
        checkState(currentState != null,
                "No experimentation state loaded for slot %s. Start the manager or call refreshNow first.",
                slotName);
        return currentState;
    }

    private RuntimeException stopUpdaters(final Iterable<ExperimentStateUpdater> updaters) {
        final List<ExperimentStateUpdater> updaterList = new ArrayList<>();
        updaters.forEach(updaterList::add);

        RuntimeException failure = null;
        for (final ExperimentStateUpdater updater : updaterList) {
            if (shouldStop(updater.state())) {
                try {
                    updater.stopAsync();
                } catch (final RuntimeException e) {
                    failure = appendFailure(failure, e);
                }
            }
        }
        for (final ExperimentStateUpdater updater : updaterList) {
            if (updater.state() == Service.State.STOPPING) {
                try {
                    updater.awaitTerminated();
                } catch (final RuntimeException e) {
                    failure = appendFailure(failure, e);
                }
            }
        }
        return failure;
    }

    private static boolean shouldStop(final Service.State serviceState) {
        return serviceState == Service.State.NEW
                || serviceState == Service.State.STARTING
                || serviceState == Service.State.RUNNING;
    }

    private static RuntimeException appendFailure(
            final RuntimeException accumulated,
            final RuntimeException nextFailure) {
        if (accumulated == null) {
            return nextFailure;
        }
        accumulated.addSuppressed(nextFailure);
        return accumulated;
    }

    private static ImmutableMap<String, ExperimentStateUpdater> buildStateUpdaters(
            final AlgorithmRepository algorithmRepository,
            final Duration stateRefreshPeriod,
            final ExperimentManagementServiceClient experimentManagementServiceClient,
            final Collection<String> slotNames) {
        Objects.requireNonNull(algorithmRepository, "algorithmRepository must not be null");
        Objects.requireNonNull(stateRefreshPeriod, "stateRefreshPeriod must not be null");
        Objects.requireNonNull(experimentManagementServiceClient, "experimentManagementServiceClient must not be null");
        Objects.requireNonNull(slotNames, "slotNames must not be null");
        checkArgument(!slotNames.isEmpty(), "slotNames must not be empty");

        final ImmutableMap.Builder<String, ExperimentStateUpdater> stateUpdaters = ImmutableMap.builder();
        for (final String slotName : slotNames) {
            Objects.requireNonNull(slotName, "slotName must not be null");
            stateUpdaters.put(slotName, new ExperimentStateUpdater(
                    slotName,
                    algorithmRepository,
                    stateRefreshPeriod,
                    experimentManagementServiceClient));
        }
        return stateUpdaters.build();
    }
}
