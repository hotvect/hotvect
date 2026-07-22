package com.hotvect.example.product;

import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;
import com.hotvect.api.data.topk.OfflineTopKRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductCompositionTest {
    private static final ProductQuery QUERY = new ProductQuery("toy vehicle", "vehicles", 30.0);
    private static final AvailableAction<Product> HELICOPTER = AvailableAction.of(
            "toy-helicopter",
            new Product("Wooden toy helicopter", "vehicles", 19.9, 0.71, 0.52),
            Map.of("action_name", "Wooden toy helicopter")
    );
    private static final AvailableAction<Product> EXCAVATOR = AvailableAction.of(
            "toy-excavator",
            new Product("Blue toy excavator", "vehicles", 25.9, 0.82, 0.41),
            Map.of("action_name", "Blue toy excavator")
    );
    private static final AvailableAction<Product> TEA_SET = AvailableAction.of(
            "wooden-tea-set",
            new Product("Wooden tea party set", "pretend-play", 31.5, 0.75, 0.63),
            Map.of("action_name", "Wooden tea party set")
    );
    private static final List<AvailableAction<Product>> CANDIDATES = List.of(HELICOPTER, EXCAVATOR, TEA_SET);

    @Test
    @SuppressWarnings("removal")
    void rankerWrapsTheScorerAndPreservesCandidateIdentityAndMetadata() {
        BulkScorer<ProductQuery, Product> scorer = new BulkScorer<>() {
            @Override
            public BulkScoreResponse<Product> score(RankingRequest<ProductQuery, Product> request) {
                return BulkScoreResponse.of(
                        List.of(
                                ScoringDecision.of("toy-helicopter", HELICOPTER.action(), 0.2, Map.of("source", "model")),
                                ScoringDecision.of("toy-excavator", EXCAVATOR.action(), 0.9, Map.of("source", "model")),
                                ScoringDecision.of("wooden-tea-set", TEA_SET.action(), 0.5, Map.of("source", "model"))
                        ),
                        FeatureStoreResponseContainer.empty(),
                        Map.of("model_version", "test")
                );
            }
        };
        var ranker = new ProductRankerFactory().apply(
                Optional.empty(),
                Map.of(),
                Map.of(ProductRankerFactory.SCORER_DEPENDENCY, AlgorithmInstance.externalAlgorithm("scorer", scorer))
        );

        RankingResponse<Product> response = ranker.rank(
                RankingRequest.ofAvailableActions("ranking-example", QUERY, CANDIDATES)
        );

        assertEquals(List.of("toy-excavator", "wooden-tea-set", "toy-helicopter"), response.decisions().stream().map(RankingDecision::actionId).toList());
        assertEquals(List.of(1, 2, 0), response.decisions().stream().map(RankingDecision::actionIndex).toList());
        assertEquals("Blue toy excavator", response.decisions().getFirst().additionalProperties().get("action_name"));
        assertEquals("model", response.decisions().getFirst().additionalProperties().get("source"));
        assertEquals("test", response.additionalProperties().get("model_version"));
    }

    @Test
    @SuppressWarnings("removal")
    void explorationRankerAppliesStableActionHashJitterAndPreservesBaseMetadata() {
        var featureStore = FeatureStoreResponseContainer.empty();
        BulkScorer<ProductQuery, Product> scorer = new BulkScorer<>() {
            @Override
            public BulkScoreResponse<Product> score(RankingRequest<ProductQuery, Product> request) {
                return BulkScoreResponse.of(
                        List.of(
                                ScoringDecision.of("toy-helicopter", HELICOPTER.action(), 0.4, Map.of("source", "model")),
                                ScoringDecision.of("toy-excavator", EXCAVATOR.action(), 0.4, Map.of()),
                                ScoringDecision.of("wooden-tea-set", TEA_SET.action(), 0.4, Map.of())
                        ),
                        featureStore,
                        Map.of("model_version", "test")
                );
            }
        };
        var explorationRanker = new ProductExplorationRankerFactory().apply(
                Optional.empty(),
                Map.of(),
                Map.of(
                        ProductExplorationRankerFactory.SCORER_DEPENDENCY,
                        AlgorithmInstance.externalAlgorithm("scorer", scorer)
                )
        );

        var request = RankingRequest.ofAvailableActions("exploration-example", QUERY, CANDIDATES);
        RankingResponse<Product> first = explorationRanker.rank(request);
        RankingResponse<Product> second = explorationRanker.rank(request);
        List<String> expectedOrder = CANDIDATES.stream()
                .map(AvailableAction::actionId)
                .sorted(Comparator
                        .comparingDouble((String actionId) -> ProductExplorationRankerFactory.explorationValue(
                                request.exampleId(),
                                actionId
                        ))
                        .reversed())
                .toList();

        assertEquals(expectedOrder, first.decisions().stream().map(RankingDecision::actionId).toList());
        assertEquals(first.decisions(), second.decisions());
        assertNotEquals(0.4, first.decisions().getFirst().score());
        assertEquals(0.4, first.decisions().getFirst().additionalProperties().get("base_score"));
        assertEquals("model", first.decisions().stream()
                .filter(decision -> decision.actionId().equals("toy-helicopter"))
                .findFirst()
                .orElseThrow()
                .additionalProperties()
                .get("source"));
        assertTrue(first.decisions().stream()
                .map(decision -> (Double) decision.additionalProperties().get("exploration_value"))
                .allMatch(value -> value >= 0.0 && value < 1.0));
        assertTrue(first.decisions().stream()
                .map(decision -> (Double) decision.additionalProperties().get("exploration_relative_jitter"))
                .allMatch(jitter -> Math.abs(jitter) <= ProductExplorationRankerFactory.MAX_RELATIVE_JITTER));
        assertSame(featureStore, first.featureStoreResponseContainer());
        assertEquals("test", first.additionalProperties().get("model_version"));
        assertEquals(
                ProductExplorationRankerFactory.POLICY_NAME,
                first.additionalProperties().get("ranking_policy")
        );
        assertEquals(
                ProductExplorationRankerFactory.MAX_RELATIVE_JITTER,
                first.additionalProperties().get("exploration_max_relative_jitter")
        );
    }

    @Test
    @SuppressWarnings("removal")
    void searchTopKRetrievesFromCatalogThenCallsTheRankerAndKeepsOnlyKDecisions() {
        AtomicReference<RankingRequest<ProductQuery, Product>> seenRequest = new AtomicReference<>();
        Ranker<ProductQuery, Product> ranker = request -> {
            seenRequest.set(request);
            return RankingResponse.newResponse(
                    List.of(
                            RankingDecision.builder("toy-excavator", 1, EXCAVATOR.action()).withScore(0.9).withAdditionalProperties(EXCAVATOR.additionalProperties()).build(),
                            RankingDecision.builder("wooden-tea-set", 2, TEA_SET.action()).withScore(0.5).withAdditionalProperties(TEA_SET.additionalProperties()).build(),
                            RankingDecision.builder("toy-helicopter", 0, HELICOPTER.action()).withScore(0.2).withAdditionalProperties(HELICOPTER.additionalProperties()).build()
                    ),
                    FeatureStoreResponseContainer.empty(),
                    Map.of("ranker", "called")
            );
        };
        var topK = new ProductSearchTopKFactory().apply(
                Optional.empty(),
                Map.of(),
                Map.of(
                        ProductSearchTopKFactory.CATALOG_DEPENDENCY,
                        AlgorithmInstance.externalAlgorithm("catalog", new ProductCatalogState(CANDIDATES)),
                        ProductSearchTopKFactory.RANKER_DEPENDENCY,
                        AlgorithmInstance.externalAlgorithm("ranker", ranker)
                )
        );
        var request = OfflineTopKRequest.newOfflineTopKRequest(
                "topk-example",
                Instant.parse("2000-01-03T12:00:00Z"),
                QUERY,
                2
        );

        var response = topK.apply(request);

        assertEquals(List.of("toy-excavator", "wooden-tea-set"), response.decisions().stream().map(decision -> decision.actionId()).toList());
        assertEquals("called", response.additionalProperties().get("ranker"));
        assertEquals(3, response.additionalProperties().get("catalog_size"));
        assertEquals(3, response.additionalProperties().get("retrieved_candidates"));
        assertInstanceOf(com.hotvect.api.data.ranking.OfflineRankingRequest.class, seenRequest.get());
        assertSame(request.featureStoreResponseContainer(), ((com.hotvect.api.data.ranking.OfflineRankingRequest<?, ?>) seenRequest.get()).featureStoreResponseContainer());
        assertEquals(QUERY, seenRequest.get().shared());
        assertEquals(3, seenRequest.get().actions().size());
        assertEquals(
                List.of("toy-excavator", "toy-helicopter", "wooden-tea-set"),
                seenRequest.get().actions().stream().map(AvailableAction::actionId).toList()
        );
        assertEquals(1, seenRequest.get().actions().getFirst().additionalProperties().get("retrieval_rank"));
    }
}
