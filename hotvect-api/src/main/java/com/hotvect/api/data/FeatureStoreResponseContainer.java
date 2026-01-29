package com.hotvect.api.data;

import com.google.common.collect.ImmutableMap;
import com.hotvect.api.data.featurestore.FeatureStoreResponse;

import java.util.Map;

/**
 * Container for feature store responses per view names.
 */
public record FeatureStoreResponseContainer(Map<String, FeatureStoreResponse> featureStoreResponses) {
    private static final FeatureStoreResponseContainer EMPTY = new FeatureStoreResponseContainer(ImmutableMap.of());
    public static FeatureStoreResponseContainer empty(){
        return EMPTY;
    }
}