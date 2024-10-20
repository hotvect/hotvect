package com.hotvect.api.algodefinition.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.transformation.CompositeTransformerFactory;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;

public interface CompositeRankingTransformerFactory<SHARED, ACTION> extends CompositeTransformerFactory<RankingTransformer<SHARED, ACTION>> {
    @Override
    RankingTransformer<SHARED, ACTION> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters, Map<String, AlgorithmInstance<?>> algorithmDependencies);

    @Override
    default SortedSet<FeatureNamespace> getUsedFeatures(Optional<JsonNode> transformerHyperparameters) {
        throw new UnsupportedOperationException("Please implement this method");
    }
}
