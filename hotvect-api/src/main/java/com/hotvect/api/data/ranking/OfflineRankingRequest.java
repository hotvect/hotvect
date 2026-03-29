package com.hotvect.api.data.ranking;

import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.OfflineRequest;

import java.util.List;
import java.util.Objects;

/**
 * Offline version of RankingRequest that includes feature store responses.
 * Used in offline training/evaluation contexts where Examples are created from JSON data.
 */
public class OfflineRankingRequest<SHARED, ACTION> extends RankingRequest<SHARED, ACTION> implements OfflineRequest<SHARED> {
    private final FeatureStoreResponseContainer featureStoreResponseContainer;

    private OfflineRankingRequest(String exampleId, SHARED shared, List<ACTION> availableActions, 
                                 FeatureStoreResponseContainer featureStoreResponseContainer) {
        super(exampleId, shared, availableActions);
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

    public static <SHARED, ACTION> OfflineRankingRequest<SHARED, ACTION> newOfflineRankingRequest(
            String exampleId, SHARED shared, List<ACTION> availableActions) {
        return new OfflineRankingRequest<>(exampleId, shared, availableActions, FeatureStoreResponseContainer.empty());
    }

    public static <SHARED, ACTION> OfflineRankingRequest<SHARED, ACTION> newOfflineRankingRequest(
            String exampleId, SHARED shared, List<ACTION> availableActions,
            FeatureStoreResponseContainer featureStoreResponseContainer) {
        return new OfflineRankingRequest<>(exampleId, shared, availableActions, featureStoreResponseContainer);
    }

    @Override
    public String toString() {
        return "OfflineRankingRequest{" +
                "exampleId='" + exampleId() + '\'' +
                ", shared=" + shared() +
                ", availableActions=" + availableActions() +
                ", featureStoreResponseContainer=" + featureStoreResponseContainer +
                '}';
    }

}
