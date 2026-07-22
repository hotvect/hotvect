package com.hotvect.api.data.ranking;

import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.Request;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Ranking request with stable action ids for every candidate.
 *
 * <p>Ranking actions must be supplied as {@link AvailableAction} values so downstream
 * rankers, scorers, audit encoders, and tie-breakers can identify decisions by {@code actionId}
 * instead of by request position. Action ids must be unique within a request.</p>
 *
 * @param <SHARED> shared context type
 * @param <ACTION> candidate action type
 */
public class RankingRequest<SHARED, ACTION> implements Request<SHARED> {
    private final String exampleId;
    private final SHARED shared;
    private final Map<String, Object> additionalProperties;
    @Deprecated
    private final List<ACTION> actions;
    private final List<AvailableAction<ACTION>> availableActions;

    /**
     * Retains the v9 raw-action constructor used by existing algorithms. This path cannot carry
     * stable action ids, so it synthesizes positional ids for compatibility.
     * TODO: Remove after raw-action ranking API migration.
     *
     * @deprecated Use {@link #ofAvailableActions(String, Object, List)} with {@link AvailableAction} values.
     */
    @Deprecated(forRemoval = true)
    public RankingRequest(
            String exampleId,
            SHARED shared,
            List<ACTION> actions
    ) {
        this(exampleId, shared, actions, Map.of());
    }

    RankingRequest(
            String exampleId,
            SHARED shared,
            List<ACTION> actions,
            Map<String, Object> additionalProperties
    ) {
        this(exampleId, shared, positionalAvailableActions(actions), additionalProperties);
    }

    RankingRequest(
            String exampleId,
            SHARED shared,
            Collection<AvailableAction<ACTION>> availableActions
    ) {
        this(exampleId, shared, availableActions, Map.of());
    }

    RankingRequest(
            String exampleId,
            SHARED shared,
            Collection<AvailableAction<ACTION>> availableActions,
            Map<String, Object> additionalProperties
    ) {
        checkArgument(exampleId != null && !exampleId.isBlank(), "exampleId cannot be null or blank");
        this.exampleId = exampleId;
        this.shared = shared;
        this.additionalProperties = Objects.requireNonNull(
                additionalProperties,
                "additionalProperties cannot be null"
        );
        Objects.requireNonNull(availableActions, "availableActions cannot be null");
        List<AvailableAction<ACTION>> normalized = new ArrayList<>(availableActions.size());
        List<ACTION> rawActionList = new ArrayList<>(availableActions.size());
        for (AvailableAction<ACTION> availableAction : availableActions) {
            normalized.add(availableAction);
            rawActionList.add(availableAction.action());
        }
        this.availableActions = Collections.unmodifiableList(normalized);
        // Preserve the assertion-mode duplicate-id check expected by existing algorithm tests.
        assert validateActionIds(this.availableActions);
        this.actions = Collections.unmodifiableList(rawActionList);
    }

    public static <SHARED, ACTION> RankingRequest<SHARED, ACTION> ofAvailableActions(
            String exampleId,
            SHARED shared,
            List<AvailableAction<ACTION>> availableActions
    ) {
        return ofAvailableActions(exampleId, shared, availableActions, Map.of());
    }

    public static <SHARED, ACTION> RankingRequest<SHARED, ACTION> ofAvailableActions(
            String exampleId,
            SHARED shared,
            List<AvailableAction<ACTION>> availableActions,
            Map<String, Object> additionalProperties
    ) {
        return new RankingRequest<>(
                exampleId,
                shared,
                (Collection<AvailableAction<ACTION>>) availableActions,
                additionalProperties
        );
    }

    public String exampleId() {
        return exampleId;
    }

    public SHARED shared() {
        return shared;
    }

    public Map<String, Object> additionalProperties() {
        return additionalProperties;
    }

    /**
     * @deprecated Use {@link #actions()} instead.
     * TODO: Remove after raw-action ranking API migration.
     */
    @Deprecated(forRemoval = true)
    public List<ACTION> availableActions() {
        return actions;
    }

    public List<AvailableAction<ACTION>> actions() {
        return availableActions;
    }

    /**
     * For backward compatibility.
     * @return
     * @deprecated Use {@link #actions()} instead.
     * TODO: Remove after raw-action ranking API migration.
     */
    @Deprecated(forRemoval = true)
    public List<ACTION> getAvailableActions() {
        return actions;
    }

    /**
     * @deprecated Use {@link #exampleId()} instead
     */
    @Deprecated(forRemoval = true)
    @Override
    public String getExampleId() {
        return exampleId;
    }

    /**
     * @deprecated Use {@link #shared()} instead
     */
    @Deprecated(forRemoval = true)
    @Override
    public SHARED getShared() {
        return shared;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RankingRequest<?, ?> that = (RankingRequest<?, ?>) o;
        return Objects.equals(exampleId, that.exampleId) &&
               Objects.equals(shared, that.shared) &&
               Objects.equals(additionalProperties, that.additionalProperties) &&
               Objects.equals(availableActions, that.availableActions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(exampleId, shared, additionalProperties, availableActions);
    }

    @Override
    public String toString() {
        return "RankingRequest{" +
                "exampleId='" + exampleId + '\'' +
                ", shared=" + shared +
                ", additionalProperties=" + additionalProperties +
                ", availableActions=" + availableActions +
                '}';
    }

    private static boolean validateActionIds(List<? extends AvailableAction<?>> availableActions) {
        Set<String> actionIds = new HashSet<>(availableActions.size());
        for (AvailableAction<?> availableAction : availableActions) {
            Objects.requireNonNull(availableAction, "availableAction cannot be null");
            if (!actionIds.add(availableAction.actionId())) {
                throw new IllegalArgumentException("Duplicate actionId: " + availableAction.actionId());
            }
        }
        return true;
    }

    private static <ACTION> List<AvailableAction<ACTION>> positionalAvailableActions(List<ACTION> actions) {
        Objects.requireNonNull(actions, "actions cannot be null");
        List<AvailableAction<ACTION>> ret = new ArrayList<>(actions.size());
        for (int i = 0; i < actions.size(); i++) {
            ret.add(AvailableAction.of(String.valueOf(i), actions.get(i)));
        }
        return ret;
    }
}
