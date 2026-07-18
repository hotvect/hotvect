package com.hotvect.onlineutils.experimentmanagement.experimentation;

import com.google.common.util.concurrent.Service;
import com.hotvect.onlineutils.experimentmanagement.models.ExperimentationState;
import com.hotvect.onlineutils.experimentmanagement.models.VariantConfiguration;
import java.util.Set;

/**
 * Lifecycle-managed entry point for EMS-backed experimentation state.
 * <p>
 * One manager owns a set of configured slots, refreshes their state in the background,
 * and performs deterministic local variant assignment against the latest successfully
 * refreshed state for each slot.
 */
public interface ExperimentationManager extends Service, AutoCloseable {

    /**
     * Returns the configured slot names managed by this instance.
     */
    Set<String> slotNames();

    /**
     * Assigns a variant for the given customer using the latest successfully refreshed
     * state for the slot.
     */
    VariantConfiguration assignVariant(String slotName, String customerNumber);

    /**
     * Returns the current immutable serving snapshot for the slot.
     * <p>
     * This snapshot contains the refreshable experimentation state. Static slot
     * configuration such as {@code totalNumberOfShards} is exposed separately.
     */
    ExperimentationState currentState(String slotName);

    /**
     * Returns the shard bucket count configured for the slot.
     * <p>
     * This value is loaded from EMS on first refresh and is expected to remain
     * immutable for the lifetime of the manager.
     */
    int totalNumberOfShards(String slotName);

    /**
     * Refreshes a single slot immediately.
     */
    void refreshNow(String slotName);

    /**
     * Refreshes all configured slots sequentially.
     * <p>
     * This is not a globally atomic multi-slot swap.
     */
    void refreshAllNow();

    @Override
    void close();
}
