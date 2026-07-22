package com.hotvect.example.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.ranking.CompositeRankerFactory;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.core.hash.HashUtils;
import com.hotvect.core.rank.BulkScoreGreedyRanker;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ProductExplorationRankerFactory implements CompositeRankerFactory<ProductQuery, Product> {
    static final String SCORER_DEPENDENCY = "example-product-scorer";
    static final String POLICY_NAME = "stable-action-hash-exploration";
    static final double MAX_RELATIVE_JITTER = 0.18;
    private static final double UNSIGNED_INT_RANGE = 1L << 32;

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
        Ranker<ProductQuery, Product> baseRanker = new BulkScoreGreedyRanker<>(scorer);
        return request -> applyExploration(request.exampleId(), baseRanker.rank(request));
    }

    private static RankingResponse<Product> applyExploration(
            String exampleId,
            RankingResponse<Product> baseResponse
    ) {
        List<RankingDecision<Product>> decisions = new ArrayList<>(baseResponse.decisions().size());
        for (RankingDecision<Product> decision : baseResponse.decisions()) {
            double baseScore = Objects.requireNonNull(
                    decision.score(),
                    "Base ranker decision score must not be null"
            );
            double explorationValue = explorationValue(exampleId, decision.actionId());
            double relativeJitter = (2.0 * explorationValue - 1.0) * MAX_RELATIVE_JITTER;
            double policyScore = baseScore * (1.0 + relativeJitter);

            Map<String, Object> properties = new LinkedHashMap<>(decision.additionalProperties());
            properties.put("base_score", baseScore);
            properties.put("exploration_value", explorationValue);
            properties.put("exploration_relative_jitter", relativeJitter);
            properties.put("exploration_max_relative_jitter", MAX_RELATIVE_JITTER);

            decisions.add(RankingDecision.builder(
                            decision.actionId(),
                            decision.actionIndex(),
                            decision.action())
                    .withScore(policyScore)
                    .withProbability(decision.probability())
                    .withAdditionalProperties(Map.copyOf(properties))
                    .build());
        }
        decisions.sort(Comparator
                .comparingDouble((RankingDecision<Product> decision) -> decision.score())
                .reversed()
                .thenComparing(RankingDecision::actionId));

        Map<String, Object> responseProperties = new LinkedHashMap<>(baseResponse.additionalProperties());
        responseProperties.put("ranking_policy", POLICY_NAME);
        responseProperties.put("exploration_max_relative_jitter", MAX_RELATIVE_JITTER);
        return RankingResponse.newResponse(
                List.copyOf(decisions),
                baseResponse.featureStoreResponseContainer(),
                Map.copyOf(responseProperties)
        );
    }

    static double explorationValue(String exampleId, String actionId) {
        int hash = HashUtils.hashUnencodedChars(exampleId + '\u0000' + actionId);
        return Integer.toUnsignedLong(hash) / UNSIGNED_INT_RANGE;
    }
}
