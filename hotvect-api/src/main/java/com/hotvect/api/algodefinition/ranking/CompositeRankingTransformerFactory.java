package com.hotvect.api.algodefinition.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.transformation.CompositeTransformerFactory;

import java.util.Optional;
import java.util.SortedSet;

public interface CompositeRankingTransformerFactory<SHARED, ACTION> extends CompositeTransformerFactory<RankingTransformer<SHARED, ACTION>> {
    @Override
    default SortedSet<Namespace> getUsedFeatures(Optional<JsonNode> transformerHyperparameters) {
        throw new UnsupportedOperationException("Please implement this method");
    }
}
