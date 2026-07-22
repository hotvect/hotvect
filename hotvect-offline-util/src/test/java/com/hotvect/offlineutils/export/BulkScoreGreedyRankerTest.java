package com.hotvect.offlineutils.export;

import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkScoreGreedyRankerTest {
    @Test
    void preservesActionIdsAndOriginalActionIndicesAfterSorting() {
        RankingRequest<Void, String> request = RankingRequest.ofAvailableActions(
                "exampleId",
                null,
                List.of(
                        AvailableAction.of("sku-c", "c"),
                        AvailableAction.of("sku-a", "a"),
                        AvailableAction.of("sku-b", "b")
                )
        );
        BulkScorer<Void, String> scorer = new BulkScorer<>() {
            @Override
            public BulkScoreResponse<String> score(RankingRequest<Void, String> rankingRequest) {
                return BulkScoreResponse.of(
                        List.of(
                                ScoringDecision.of("sku-c", "c", 0.2, Map.of("source", "c")),
                                ScoringDecision.of("sku-a", "a", 0.9, Map.of("source", "a")),
                                ScoringDecision.of("sku-b", "b", 0.4, Map.of("source", "b"))
                        ),
                        FeatureStoreResponseContainer.empty()
                );
            }
        };

        RankingResponse<String> response = new BulkScoreGreedyRanker<>(scorer).rank(request);

        assertEquals(List.of("sku-a", "sku-b", "sku-c"),
                response.rankingDecisions().stream().map(RankingDecision::actionId).toList());
        assertEquals(List.of(1, 2, 0),
                response.rankingDecisions().stream().map(RankingDecision::actionIndex).toList());
    }

    @Test
    void rejectsScoringDecisionsReturnedOutOfRequestOrder() {
        assertTrue(assertionsEnabled());
        RankingRequest<Void, String> request = RankingRequest.ofAvailableActions(
                "exampleId",
                null,
                List.of(
                        AvailableAction.of("sku-a", "a"),
                        AvailableAction.of("sku-b", "b"),
                        AvailableAction.of("sku-c", "c")
                )
        );
        BulkScorer<Void, String> scorer = new BulkScorer<>() {
            @Override
            public BulkScoreResponse<String> score(RankingRequest<Void, String> rankingRequest) {
                return BulkScoreResponse.of(
                        List.of(
                                ScoringDecision.of("sku-b", "b", 0.9, Map.of("source", "b")),
                                ScoringDecision.of("sku-c", "c", 0.1, Map.of("source", "c")),
                                ScoringDecision.of("sku-a", "a", 0.5, Map.of("source", "a"))
                        ),
                        FeatureStoreResponseContainer.empty()
                );
            }
        };

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new BulkScoreGreedyRanker<>(scorer).rank(request)
        );
        assertEquals(
                "BulkScorer must preserve request action order; score at position 0 has action id sku-b, expected sku-a",
                error.getMessage()
        );
    }

    @Test
    void mergesAvailableActionAndScoreAdditionalProperties() {
        RankingRequest<Void, String> request = RankingRequest.ofAvailableActions(
                "exampleId",
                null,
                List.of(AvailableAction.of("sku-a", "a", Map.of("source", "request", "requestOnly", "yes")))
        );
        BulkScorer<Void, String> scorer = new BulkScorer<>() {
            @Override
            public BulkScoreResponse<String> score(RankingRequest<Void, String> rankingRequest) {
                return BulkScoreResponse.of(
                        List.of(ScoringDecision.of(
                                "sku-a",
                                "a",
                                1.0,
                                Map.of("source", "score", "scoreOnly", "yes")
                        )),
                        FeatureStoreResponseContainer.empty()
                );
            }
        };

        RankingResponse<String> response = new BulkScoreGreedyRanker<>(scorer).rank(request);

        assertEquals(
                Map.of("source", "score", "requestOnly", "yes", "scoreOnly", "yes"),
                response.rankingDecisions().get(0).additionalProperties()
        );
    }

    private static boolean assertionsEnabled() {
        boolean enabled = false;
        assert enabled = true;
        return enabled;
    }
}
