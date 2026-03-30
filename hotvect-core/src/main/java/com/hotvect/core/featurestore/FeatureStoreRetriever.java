package com.hotvect.core.featurestore;

import java.util.Map;

import com.hotvect.api.data.featurestore.FeatureStoreResponse;
import com.hotvect.api.data.ranking.RankingRequest;

public interface FeatureStoreRetriever<SHARED, ACTION> {
    Map<String, FeatureStoreResponse> fetch(RankingRequest<SHARED, ACTION> request);
}
