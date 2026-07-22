package com.hotvect.api.data.ranking;

import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.OfflineRequest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Offline version of RankingRequest that includes feature store responses.
 * Used in offline training/evaluation contexts where Examples are created from JSON data.
 */
public class OfflineRankingRequest<SHARED, ACTION> extends RankingRequest<SHARED, ACTION> implements OfflineRequest<SHARED> {
    private final FeatureStoreResponseContainer featureStoreResponseContainer;

    private OfflineRankingRequest(
            String exampleId,
            SHARED shared,
            List<ACTION> actions,
            FeatureStoreResponseContainer featureStoreResponseContainer
    ) {
        super(exampleId, shared, actions, Map.of());
        this.featureStoreResponseContainer = Objects.requireNonNull(
                featureStoreResponseContainer,
                "featureStoreResponseContainer cannot be null"
        );
    }

    private OfflineRankingRequest(
            String exampleId,
            SHARED shared,
            Collection<AvailableAction<ACTION>> availableActions,
            FeatureStoreResponseContainer featureStoreResponseContainer
    ) {
        this(exampleId, shared, availableActions, featureStoreResponseContainer, Map.of());
    }

    private OfflineRankingRequest(
            String exampleId,
            SHARED shared,
            Collection<AvailableAction<ACTION>> availableActions,
            FeatureStoreResponseContainer featureStoreResponseContainer,
            Map<String, Object> additionalProperties
    ) {
        super(exampleId, shared, availableActions, additionalProperties);
        this.featureStoreResponseContainer = Objects.requireNonNull(
                featureStoreResponseContainer,
                "featureStoreResponseContainer cannot be null"
        );
    }

    @Override
    public FeatureStoreResponseContainer featureStoreResponseContainer() {
        return featureStoreResponseContainer;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        OfflineRankingRequest<?, ?> that = (OfflineRankingRequest<?, ?>) o;
        return Objects.equals(featureStoreResponseContainer, that.featureStoreResponseContainer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), featureStoreResponseContainer);
    }

    public static <SHARED, ACTION> OfflineRankingRequest<SHARED, ACTION> ofAvailableActions(
            String exampleId, SHARED shared, List<AvailableAction<ACTION>> availableActions) {
        return new OfflineRankingRequest<>(
                exampleId,
                shared,
                (Collection<AvailableAction<ACTION>>) availableActions,
                FeatureStoreResponseContainer.empty(),
                Map.of()
        );
    }

    public static <SHARED, ACTION> OfflineRankingRequest<SHARED, ACTION> ofAvailableActions(
            String exampleId, SHARED shared, List<AvailableAction<ACTION>> availableActions,
            Map<String, Object> additionalProperties) {
        return new OfflineRankingRequest<>(
                exampleId,
                shared,
                (Collection<AvailableAction<ACTION>>) availableActions,
                FeatureStoreResponseContainer.empty(),
                additionalProperties
        );
    }

    public static <SHARED, ACTION> OfflineRankingRequest<SHARED, ACTION> ofAvailableActions(
            String exampleId, SHARED shared, List<AvailableAction<ACTION>> availableActions,
            FeatureStoreResponseContainer featureStoreResponseContainer) {
        return new OfflineRankingRequest<>(
                exampleId,
                shared,
                (Collection<AvailableAction<ACTION>>) availableActions,
                featureStoreResponseContainer,
                Map.of()
        );
    }

    public static <SHARED, ACTION> OfflineRankingRequest<SHARED, ACTION> ofAvailableActions(
            String exampleId, SHARED shared, List<AvailableAction<ACTION>> availableActions,
            FeatureStoreResponseContainer featureStoreResponseContainer,
            Map<String, Object> additionalProperties) {
        return new OfflineRankingRequest<>(
                exampleId,
                shared,
                (Collection<AvailableAction<ACTION>>) availableActions,
                featureStoreResponseContainer,
                additionalProperties
        );
    }

    /**
     * Legacy raw-action factory for old algorithms that still call this API with {@code List<ACTION>}.
     * This path cannot carry stable action ids, so it synthesizes positional ids for compatibility.
     * TODO: Remove this overload after legacy algorithms migrate to {@link AvailableAction}.
     *
     * @deprecated Use {@link #ofAvailableActions(String, Object, List)} with {@link AvailableAction} values.
     */
    @Deprecated(forRemoval = true)
    public static <SHARED, ACTION> OfflineRankingRequest<SHARED, ACTION> newOfflineRankingRequest(
            String exampleId, SHARED shared, List<ACTION> availableActions) {
        return new OfflineRankingRequest<>(exampleId, shared, availableActions, FeatureStoreResponseContainer.empty());
    }

    /**
     * Legacy raw-action factory for old algorithms that still call this API with {@code List<ACTION>}.
     * This path cannot carry stable action ids, so it synthesizes positional ids for compatibility.
     * TODO: Remove this overload after legacy algorithms migrate to {@link AvailableAction}.
     *
     * @deprecated Use {@link #ofAvailableActions(String, Object, List, FeatureStoreResponseContainer)}
     * with {@link AvailableAction} values.
     */
    @Deprecated(forRemoval = true)
    public static <SHARED, ACTION> OfflineRankingRequest<SHARED, ACTION> newOfflineRankingRequest(
            String exampleId, SHARED shared, List<ACTION> availableActions,
            FeatureStoreResponseContainer featureStoreResponseContainer) {
        return new OfflineRankingRequest<>(exampleId, shared, availableActions, featureStoreResponseContainer);
    }

    /**
     * Legacy raw-action factory retained for callers compiled against the collection-based v9 API.
     * TODO: Remove this overload after legacy algorithms migrate to {@link AvailableAction}.
     *
     * @deprecated Use {@link #ofAvailableActions(String, Object, List)} with {@link AvailableAction} values.
     */
    @Deprecated(forRemoval = true)
    public static <SHARED, ACTION> OfflineRankingRequest<SHARED, ACTION> newOfflineRankingRequest(
            String exampleId, SHARED shared, Collection<ACTION> availableActions) {
        return new OfflineRankingRequest<>(exampleId, shared, new ArrayList<>(availableActions), FeatureStoreResponseContainer.empty());
    }

    /**
     * Legacy raw-action factory retained for callers compiled against the collection-based v9 API.
     * TODO: Remove this overload after legacy algorithms migrate to {@link AvailableAction}.
     *
     * @deprecated Use {@link #ofAvailableActions(String, Object, List, FeatureStoreResponseContainer)}
     * with {@link AvailableAction} values.
     */
    @Deprecated(forRemoval = true)
    public static <SHARED, ACTION> OfflineRankingRequest<SHARED, ACTION> newOfflineRankingRequest(
            String exampleId, SHARED shared, Collection<ACTION> availableActions,
            FeatureStoreResponseContainer featureStoreResponseContainer) {
        return new OfflineRankingRequest<>(exampleId, shared, new ArrayList<>(availableActions), featureStoreResponseContainer);
    }

    @Override
    public String toString() {
        return "OfflineRankingRequest{" +
                "exampleId='" + exampleId() + '\'' +
                ", shared=" + shared() +
                ", additionalProperties=" + additionalProperties() +
                ", availableActions=" + actions() +
                ", featureStoreResponseContainer=" + featureStoreResponseContainer +
                '}';
    }

}
