package com.hotvect.example.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.reflect.TypeToken;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import com.hotvect.api.algodefinition.ranking.CompositeRankerFactory;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.core.rank.BulkScoreGreedyRanker;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public final class ProductRankerFactory implements CompositeRankerFactory<ProductQuery, Product> {
    static final String SCORER_DEPENDENCY = "example-product-scorer";

    @Override
    public Ranker<ProductQuery, Product> create(
            ExecutionContext executionContext,
            Optional<LocalStateStorage> localStateStorage,
            Optional<JsonNode> configuration,
            Map<String, InputStream> parameters,
            AlgorithmDependencies dependencies
    ) {
        BulkScorer<ProductQuery, Product> scorer = dependencies.only(
                SCORER_DEPENDENCY,
                new TypeToken<BulkScorer<ProductQuery, Product>>() {});
        return new BulkScoreGreedyRanker<>(scorer);
    }
}
