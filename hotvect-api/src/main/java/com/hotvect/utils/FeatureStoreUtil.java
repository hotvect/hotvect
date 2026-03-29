package com.hotvect.utils;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableMap;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.featurestore.FeatureStore;
import com.hotvect.api.data.featurestore.FeatureStoreResponse;
import com.hotvect.api.data.featurestore.SimpleFeatureStoreResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * A utility class for managing the {@link FeatureStore} and its associated components.
 */
public final class FeatureStoreUtil {

    private FeatureStoreUtil() {
    }

    /**
     * Deep merges two feature store response containers by view name.
     * <p>
     * If a view name exists in both containers, responses are merged using {@link #deepMerge(FeatureStoreResponse, FeatureStoreResponse)}.
     * If a view name exists in only one container, the response is deeply copied.
     * <p>
     * Empty containers are handled: if one is empty, the other is deeply copied and returned.
     * <p>
     * Note: This method copies and merges the map structure (entity IDs and feature maps) but does not
     * deep-copy or deep-merge nested feature values (arrays, objects, etc.). Those are copied by reference.
     *
     * @param first  the first container to merge (must be non-null)
     * @param second the second container to merge (must be non-null)
     * @return a new container containing the merged responses
     * @throws IllegalArgumentException if either container is null
     */
    public static FeatureStoreResponseContainer deepMerge(FeatureStoreResponseContainer first,
                                                          FeatureStoreResponseContainer second) {

        checkArgument(first != null, "first container cannot be null");
        checkArgument(second != null, "second container cannot be null");

        if (first.featureStoreResponses().isEmpty()) {
            return second.featureStoreResponses().isEmpty() ? FeatureStoreResponseContainer.empty() : deepCopy(second);
        } else if (second.featureStoreResponses().isEmpty()) {
            return deepCopy(first);
        }

        Map<String, FeatureStoreResponse> merged = new HashMap<>();

        for (var entry : first.featureStoreResponses().entrySet()) {
            var viewName = entry.getKey();
            var firstResponse = entry.getValue();
            var secondResponse = second.featureStoreResponses().get(viewName);

            if (secondResponse != null) {
                merged.put(viewName, deepMerge(firstResponse, secondResponse));
            } else {
                merged.put(viewName, deepCopy(firstResponse));
            }
        }

        for (var entry : second.featureStoreResponses().entrySet()) {
            var viewName = entry.getKey();
            var secondResponse = entry.getValue();

            if (!merged.containsKey(viewName)) {
                merged.put(viewName, deepCopy(secondResponse));
            }
        }

        return new FeatureStoreResponseContainer(ImmutableMap.copyOf(merged));
    }

    /**
     * Deep merges two feature store responses.
     * <p>
     * Merges entities and failures from both responses:
     * <ul>
     *   <li>Entities: merged with second overwriting first for conflicts</li>
     *   <li>Failures: combined with newline separators if both have failures</li>
     * </ul>
     * <p>
     * Returns a {@link SimpleFeatureStoreResponse}:
     * <ul>
     *   <li>Success: If no failures present (entities may be empty)</li>
     *   <li>Failure: If failures present and no entities</li>
     *   <li>Partial Failure: If both failures and entities are present</li>
     * </ul>
     * <p>
     * Note: This method copies and merges the map structure (entity IDs and feature maps) but does not
     * deep-copy or deep-merge nested feature values (arrays, objects, etc.). Those are copied by reference.
     *
     * @param first  the first response to merge
     * @param second the second response to merge
     * @return a new merged response
     * @throws IllegalArgumentException if either argument is null
     */
    public static FeatureStoreResponse deepMerge(FeatureStoreResponse first,
                                                 FeatureStoreResponse second) {

        checkArgument(first != null, "first response cannot be null");
        checkArgument(second != null, "second response cannot be null");

        var mergedFailures = deepMergeFailures(first, second);
        var mergedEntities = deepMergeEntities(first, second);

        if (mergedFailures.isEmpty()) { // Both succeeded
            return SimpleFeatureStoreResponse.success(mergedEntities);
        } else if (mergedEntities.isEmpty()) { // Both failed
            return SimpleFeatureStoreResponse.failure(mergedFailures);
        } else { // Partial failure
            return SimpleFeatureStoreResponse.partial(mergedEntities, mergedFailures);
        }
    }

    /**
     * Creates a deep copy of a feature store response container.
     * <p>
     * All responses in the container are deeply copied. Returns an empty container if the input is empty.
     * <p>
     * Note: This method copies the map structure (entity IDs and feature maps) but does not deep-copy
     * nested feature values (arrays, objects, etc.). Those are copied by reference.
     *
     * @param container the container to copy (must be non-null)
     * @return a new container containing deep copies of all responses
     * @throws IllegalArgumentException if container is null
     */
    public static FeatureStoreResponseContainer deepCopy(FeatureStoreResponseContainer container) {
        checkArgument(container != null, "container cannot be null");

        if (container.featureStoreResponses().isEmpty()) {
            return FeatureStoreResponseContainer.empty();
        }

        Map<String, FeatureStoreResponse> copied = new HashMap<>();

        for (var entry : container.featureStoreResponses().entrySet()) {
            copied.put(entry.getKey(), deepCopy(entry.getValue()));
        }

        return new FeatureStoreResponseContainer(ImmutableMap.copyOf(copied));
    }

    /**
     * Creates a deep copy of a feature store response.
     * <p>
     * All entities and their features are deeply copied. Returns a {@link SimpleFeatureStoreResponse}
     * preserving the original response's state (success, failure, or partial failure):
     * <ul>
     *   <li>Success: If no failures present (entities may be empty)</li>
     *   <li>Failure: If failures present and no entities</li>
     *   <li>Partial Failure: If both failures and entities are present</li>
     * </ul>
     * <p>
     * Note: This method copies the map structure but does not deep-copy nested feature values
     * (arrays, objects, etc.). Those are copied by reference.
     *
     * @param response the response to copy
     * @return a new deep copy of the response
     * @throws IllegalArgumentException if response is null
     */
    public static FeatureStoreResponse deepCopy(FeatureStoreResponse response) {
        checkArgument(response != null, "response cannot be null");

        if (response.getRequestFailure().isPresent()) {
            var failure = response.getRequestFailure().get();

            if (response.getAllEntities().isEmpty()) {
                return SimpleFeatureStoreResponse.failure(failure);
            }

            return SimpleFeatureStoreResponse.partial(deepCopy(response.getAllEntities()), failure);
        }

        return SimpleFeatureStoreResponse.success(deepCopy(response.getAllEntities()));
    }

    private static String deepMergeFailures(FeatureStoreResponse first, FeatureStoreResponse second) {
        if (first.getRequestFailure().isPresent()) {
            var firstFailure = first.getRequestFailure().get();

            if (second.getRequestFailure().isPresent()) {
                var secondFailure = second.getRequestFailure().get();

                return firstFailure + "\n" + secondFailure;
            }

            return firstFailure;
        }

        return second.getRequestFailure().orElse("");
    }

    private static Map<Map<String, Object>, Map<String, Object>> deepMergeEntities(FeatureStoreResponse first,
                                                                                   FeatureStoreResponse second) {

        Map<Map<String, Object>, Map<String, Object>> merged = new HashMap<>();

        for (var entry : first.getAllEntities().entrySet()) {
            var entityId = ImmutableMap.copyOf(entry.getKey());
            var features = ImmutableMap.copyOf(entry.getValue());
            merged.put(entityId, features);
        }

        for (var entry : second.getAllEntities().entrySet()) {
            var entityId = ImmutableMap.copyOf(entry.getKey());
            var builder = ImmutableMap.<String, Object>builder();

            var firstFeatures = merged.get(entityId);
            if (firstFeatures != null) {
                builder.putAll(firstFeatures);
            }
            builder.putAll(entry.getValue());

            merged.put(entityId, builder.buildKeepingLast()); // overwrites the first with the second.
        }

        return ImmutableMap.copyOf(merged);
    }

    private static Map<Map<String, Object>, Map<String, Object>> deepCopy(Map<Map<String, Object>, Map<String, Object>> entities) {
        Map<Map<String, Object>, Map<String, Object>> copied = new HashMap<>();

        for (var entry : entities.entrySet()) {
            var entityId = ImmutableMap.copyOf(entry.getKey());
            var features = ImmutableMap.copyOf(entry.getValue());
            copied.put(entityId, features);
        }

        return ImmutableMap.copyOf(copied);
    }

}
