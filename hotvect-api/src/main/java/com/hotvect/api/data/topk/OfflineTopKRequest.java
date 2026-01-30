package com.hotvect.api.data.topk;

import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.OfflineRequest;

import java.time.Instant;
import java.util.Objects;

/**
 * Offline version of TopKRequest that includes feature store responses.
 * Used in offline training/evaluation contexts where Examples are created from JSON data.
 */
public class OfflineTopKRequest<SHARED> extends TopKRequest<SHARED> implements OfflineRequest<SHARED> {
    private final FeatureStoreResponseContainer featureStoreResponseContainer;

    private OfflineTopKRequest(String exampleId, Instant occurredAt, SHARED shared, int k,
                              FeatureStoreResponseContainer featureStoreResponseContainer) {
        super(exampleId, occurredAt, shared, k);
        this.featureStoreResponseContainer = featureStoreResponseContainer;
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
        OfflineTopKRequest<?> that = (OfflineTopKRequest<?>) o;
        return Objects.equals(featureStoreResponseContainer, that.featureStoreResponseContainer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), featureStoreResponseContainer);
    }

    public static <SHARED> OfflineTopKRequest<SHARED> newOfflineTopKRequest(
            String exampleId, Instant occurredAt, SHARED shared, int k) {
        return new OfflineTopKRequest<>(exampleId, occurredAt, shared, k, null);
    }

    public static <SHARED> OfflineTopKRequest<SHARED> newOfflineTopKRequest(
            String exampleId, Instant occurredAt, SHARED shared, int k,
            FeatureStoreResponseContainer featureStoreResponseContainer) {
        return new OfflineTopKRequest<>(exampleId, occurredAt, shared, k, featureStoreResponseContainer);
    }

    @Override
    public String toString() {
        return "OfflineTopKRequest{" +
                "exampleId='" + exampleId() + '\'' +
                ", occurredAt=" + occurredAt() +
                ", shared=" + shared() +
                ", k=" + k() +
                ", featureStoreResponseContainer=" + featureStoreResponseContainer +
                '}';
    }
}