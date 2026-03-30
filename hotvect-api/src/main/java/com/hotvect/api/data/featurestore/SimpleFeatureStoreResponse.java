package com.hotvect.api.data.featurestore;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Collections.emptyMap;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * A simple, in-memory POJO implementation of {@link FeatureStoreResponse}.
 * <p>
 * This class can represent success, failure, or partial failure scenarios:
 * <ul>
 *   <li><b>Success:</b> Request succeeded with no failures. Entities may be present or empty
 *       (empty means no records matched the query, which is still a successful response).</li>
 *   <li><b>Failure:</b> Request failed with a failure message and no entities.</li>
 *   <li><b>Partial Failure:</b> Request partially succeeded - some entities retrieved successfully
 *       but failures also occurred (the same as success scenario, entities may be present or empty.</li>
 * </ul>
 * <p>
 * Instances should be created using the factory methods:
 * <ul>
 *   <li>{@link #success(Map)} for successful responses</li>
 *   <li>{@link #failure(String)} for failed responses</li>
 *   <li>{@link #partial(Map, String)} for partial failures</li>
 * </ul>
 * <p>
 * The builder pattern is also available for backward compatibility, but factory methods are preferred.
 * Please do not use the builder anymore.
 */
public final class SimpleFeatureStoreResponse implements FeatureStoreResponse {

    private final Map<Map<String, Object>, Map<String, Object>> allEntities;
    private final String requestFailure;

    /**
     * Private constructor. Use factory methods ({@link #success(Map)},
     * {@link #failure(String)}, {@link #partial(Map, String)}) to create instances.
     *
     * @param allEntities    the successfully retrieved entities (entity ID → features map), may be empty
     * @param requestFailure the failure message, or {@code null} if no failure
     */
    private SimpleFeatureStoreResponse(Map<Map<String, Object>, Map<String, Object>> allEntities, String requestFailure) {
        this.allEntities = Collections.unmodifiableMap(requireNonNull(allEntities, "allEntities cannot be null"));
        this.requestFailure = requestFailure;
    }

    /**
     * Creates a successful response with no failures.
     * <p>
     * The request succeeded, but entities may be empty if no records matched the query.
     *
     * @param allEntities the successfully retrieved entities (entity ID → feature map), may be empty or null
     * @return a new successful response
     */
    public static SimpleFeatureStoreResponse success(Map<Map<String, Object>, Map<String, Object>> allEntities) {
        return new SimpleFeatureStoreResponse(allEntities != null ? allEntities : emptyMap(), null);
    }

    /**
     * Creates a failed response with no entities and a failure message.
     *
     * @param failureMessage the failure message (must be non-null and non-blank)
     * @return a new failed response
     * @throws IllegalArgumentException if failureMessage is null or blank
     */
    public static SimpleFeatureStoreResponse failure(String failureMessage) {
        checkArgument(failureMessage != null && !failureMessage.isBlank(), "failureMessage must be non-null and non-blank");

        return new SimpleFeatureStoreResponse(emptyMap(), failureMessage);
    }

    /**
     * Creates a partial failure response with both entities and a failure message.
     *
     * @param allEntities    the successfully retrieved entities (entity ID → feature map), may be empty or null
     * @param failureMessage the failure message (must be non-null and non-blank)
     * @return a new partial failure response
     * @throws IllegalArgumentException if failureMessage is null or blank
     */
    public static SimpleFeatureStoreResponse partial(Map<Map<String, Object>, Map<String, Object>> allEntities, String failureMessage) {
        checkArgument(failureMessage != null && !failureMessage.isBlank(), "failureMessage must be non-null and non-blank");

        return new SimpleFeatureStoreResponse(allEntities != null ? allEntities : emptyMap(), failureMessage);
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
        return ofNullable(requestFailure);
    }

    /**
     * Returns a new builder for creating a {@link SimpleFeatureStoreResponse}.
     * <p>
     * <b>Deprecated:</b> This method is deprecated. Use factory methods ({@link #success(Map)},
     * {@link #failure(String)}, {@link #partial(Map, String)}) instead for better clarity at call sites.
     *
     * @return a new {@link Builder} instance
     * @deprecated Use factory methods ({@link #success(Map)}, {@link #failure(String)}, {@link #partial(Map, String)}) instead
     */
    @Deprecated
    public static Builder builder() {
        return new Builder();
    }

    /**
     * A builder for creating immutable {@link SimpleFeatureStoreResponse} instances.
     * <p>
     * <b>Deprecated:</b> This class is deprecated. Use factory methods ({@link SimpleFeatureStoreResponse#success(Map)},
     * {@link SimpleFeatureStoreResponse#failure(String)}, {@link SimpleFeatureStoreResponse#partial(Map, String)}) instead.
     *
     * @deprecated Use factory methods instead
     */
    @Deprecated
    public static final class Builder {
        private Map<Map<String, Object>, Map<String, Object>> allEntities;
        private String requestFailure;

        private Builder() {
        }

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
            return new SimpleFeatureStoreResponse(allEntities != null ? allEntities : emptyMap(), requestFailure);
        }
    }

}
