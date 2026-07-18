package com.hotvect.example.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.ranking.CompositeRankerFactory;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.core.rank.BulkScoreGreedyRanker;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public final class ProductRankerFactory implements CompositeRankerFactory<ProductQuery, Product> {
    static final String SCORER_DEPENDENCY = "example-product-scorer";

    @Override
    @SuppressWarnings({"unchecked", "removal"})
    public Ranker<ProductQuery, Product> apply(
            Optional<JsonNode> configuration,
            Map<String, InputStream> parameters,
            Map<String, AlgorithmInstance<?>> dependencies
    ) {
        BulkScorer<ProductQuery, Product> scorer = (BulkScorer<ProductQuery, Product>) dependencies
                .get(SCORER_DEPENDENCY)
                .algorithm();
        return new BulkScoreGreedyRanker<>(scorer);
    }
}
