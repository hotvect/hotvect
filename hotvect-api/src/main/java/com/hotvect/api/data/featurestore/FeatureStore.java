package com.hotvect.api.data.featurestore;

import com.hotvect.api.algorithms.Algorithm;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Provides access to feature store data from within algorithms.
 *
 * <p><strong>Error Handling Contract:</strong></p>
 * <ul>
 *   <li>Methods of this interface must <strong>never throw exceptions</strong>.</li>
 *   <li>Methods must <strong>never return failed futures</strong>.</li>
 *   <li>Methods must <strong>always return a successfully completed future</strong>
 *       containing a response object. If an error occurs (e.g., transient issues,
 *       invalid input, or any unexpected condition), the response object must
 *       include a failure cause message.</li>
 * </ul>
 */
public interface FeatureStore extends Algorithm {

    /**
     * Retrieves features for the given list of IDs.
     * <p>
     * Each element in the {@code ids} list is a map from column name to ID value,
     * allowing support for composite keys (multiple columns as identifiers).
     * </p>
     */
    CompletableFuture<FeatureStoreResponse> getFeatures(List<Map<String, Object>> ids, String viewName,
                                                        int viewVersion, String... featureNames);


    /**
     * Convenience method for callers who want to use a list of feature names
     * instead of varargs.
     */
    default CompletableFuture<FeatureStoreResponse> getFeatures(List<Map<String, Object>> ids, String viewName,
                                                                int viewVersion, List<String> featureNames) {
        String[] featureNamesArray = featureNames.toArray(new String[0]);
        return getFeatures(ids, viewName, viewVersion, featureNamesArray);
    }

}