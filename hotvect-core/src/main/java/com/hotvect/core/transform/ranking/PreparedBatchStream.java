package com.hotvect.core.transform.ranking;

import com.hotvect.api.data.featurestore.FeatureStoreResponse;
import com.hotvect.api.data.ranking.TransformedAction;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public record PreparedBatchStream<ACTION>(
        Stream<List<TransformedAction<ACTION>>> batchStream,
        Map<String, FeatureStoreResponse> featureStoreResponses
) {}
