package com.hotvect.api.data.featurestore;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * A simple, in-memory POJO implementation of {@link FeatureStoreResponse}.
 * It holds the complete result of a feature store lookup, including any combination
 * of successfully fetched data and various errors.
 * <p>
 * Instances should be created using the {@link Builder}.
 */
public final class SimpleFeatureStoreResponse implements FeatureStoreResponse {

    private final Map<Map<String, Object>, Map<String, Object>> allEntities;
    private final String requestFailure;

    /**
     * Private constructor. Use the {@link #builder()} to create instances.
     */
    private SimpleFeatureStoreResponse(
            Map<Map<String, Object>, Map<String, Object>> allEntities,
            String requestFailure) {
        this.allEntities = allEntities != null ? Collections.unmodifiableMap(allEntities) : Collections.emptyMap();
        this.requestFailure = requestFailure;
    }

    /**
     * Returns a new builder for creating a {@link SimpleFeatureStoreResponse}.
     *
     * @return a new {@link Builder} instance.
     */
    public static Builder builder() {
        return new Builder();
    }


    @Override
    public Map<String, Object> getEntity(Map<String, Object> entityId) {
        return allEntities.get(entityId);
    }

    @Override
    public Map<Map<String, Object>, Map<String, Object>> getAllEntities() {
        return allEntities;
    }

    @Override
    public Optional<String> getRequestFailure() {
        return Optional.ofNullable(requestFailure);
    }

    /**
     * A builder for creating immutable {@link SimpleFeatureStoreResponse} instances.
     */
    public static final class Builder {
        private Map<Map<String, Object>, Map<String, Object>> allEntities;
        private String requestFailure;

        private Builder() {}

        public Builder allEntities(Map<Map<String, Object>, Map<String, Object>> allEntities) {
            this.allEntities = allEntities;
            return this;
        }

        public Builder requestFailure(String requestFailure) {
            this.requestFailure = requestFailure;
            return this;
        }

        /**
         * Builds the {@link SimpleFeatureStoreResponse} instance.
         *
         * @return a new {@link SimpleFeatureStoreResponse}.
         * @throws IllegalStateException if a {@code requestFailure} is present along with any
         * entity or feature data/errors.
         */
        public SimpleFeatureStoreResponse build() {
            // Validation logic: if the whole request failed, there should be no partial data.
            if (requestFailure != null) {
                boolean hasAnyData = allEntities != null && !allEntities.isEmpty();

                if (hasAnyData) {
                    throw new IllegalStateException("Cannot have entity/feature data or errors when a request-level failure is present.");
                }
            }
            return new SimpleFeatureStoreResponse(allEntities, requestFailure);
        }
    }
}