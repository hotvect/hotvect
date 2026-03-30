package com.hotvect.core.transform.ranking;

import java.util.Map;

import com.hotvect.api.data.featurestore.FeatureStoreResponse;

public record SharedContext<SHARED>(
        SHARED shared,
        Map<String, FeatureStoreResponse> featureStoreResponses
) {}
