package com.hotvect.core.rank;

import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RankerToBulkScorerTest {
    @Test
    void projectsRankerOutputByActionId() {
        RankingRequest<Void, String> request = twoActionRequest();
        Ranker<Void, String> ranker = ignored -> RankingResponse.newResponse(
                List.of(
                        RankingDecision.builder("sku-a", "a")
                                .withScore(2.0)
                                .withAdditionalProperties(ImmutableMap.of("source", "a"))
                                .build(),
                        RankingDecision.builder("sku-b", "b")
                                .withScore(1.0)
                                .withAdditionalProperties(ImmutableMap.of("source", "b"))
                                .build()
                )
        );

        var scorer = new RankerToBulkScorer<>(ranker);
        var scoreResponse = scorer.score(request);
        var scoreList = scorer.apply(request);

        assertEquals(List.of(1.0, 2.0), scoreResponse.decisions().stream().map(x -> x.score()).toList());
        assertEquals(List.of("sku-b", "sku-a"), scoreResponse.decisions().stream().map(x -> x.actionId()).toList());
        assertEquals(1.0, scoreList.getDouble(0));
        assertEquals(2.0, scoreList.getDouble(1));
        assertEquals("b", scoreResponse.decisions().get(0).additionalProperties().get("source"));
        assertEquals("a", scoreResponse.decisions().get(1).additionalProperties().get("source"));
        assertEquals("request-b", scoreResponse.decisions().get(0).additionalProperties().get("request"));
        assertEquals("request-a", scoreResponse.decisions().get(1).additionalProperties().get("request"));
    }

    @Test
    @SuppressWarnings("removal")
    void ignoresLegacyActionIndexWhenProjectingByActionId() {
        RankingRequest<Void, String> request = twoActionRequest();
        Ranker<Void, String> ranker = ignored -> RankingResponse.newResponse(
                List.of(
                        RankingDecision.builder("sku-a", 0, "a").withScore(2.0).build(),
                        RankingDecision.builder("sku-b", 1, "b").withScore(1.0).build()
                )
        );

        var scorer = new RankerToBulkScorer<>(ranker);

        assertEquals(List.of(1.0, 2.0), scorer.score(request).decisions().stream().map(x -> x.score()).toList());
    }

    @Test
    void rejectsUnknownActionId() {
        RankingRequest<Void, String> request = twoActionRequest();
        Ranker<Void, String> ranker = ignored -> RankingResponse.newResponse(
                List.of(
                        RankingDecision.builder("sku-a", "a").withScore(2.0).build(),
                        RankingDecision.builder("sku-c", "c").withScore(3.0).build()
                )
        );

        var scorer = new RankerToBulkScorer<>(ranker);

        assertEquals(
                "Ranker returned decision for unknown action id: sku-c",
                assertThrows(IllegalArgumentException.class, () -> scorer.score(request)).getMessage()
        );
    }

    @Test
    void rejectsDuplicateActionId() {
        RankingRequest<Void, String> request = twoActionRequest();
        Ranker<Void, String> ranker = ignored -> RankingResponse.newResponse(
                List.of(
                        RankingDecision.builder("sku-a", "a").withScore(2.0).build(),
                        RankingDecision.builder("sku-a", "a").withScore(3.0).build()
                )
        );

        var scorer = new RankerToBulkScorer<>(ranker);

        assertEquals(
                "Ranker returned duplicate decision for action id: sku-a",
                assertThrows(IllegalArgumentException.class, () -> scorer.score(request)).getMessage()
        );
    }

    @Test
    void rejectsIncompleteRankerOutput() {
        RankingRequest<Void, String> request = twoActionRequest();
        Ranker<Void, String> ranker = ignored -> RankingResponse.newResponse(
                List.of(RankingDecision.builder("sku-a", "a").withScore(2.0).build())
        );

        var scorer = new RankerToBulkScorer<>(ranker);

        assertEquals(
                "Ranker returned 1 decisions for 2 actions",
                assertThrows(IllegalArgumentException.class, () -> scorer.score(request)).getMessage()
        );
    }

    private RankingRequest<Void, String> twoActionRequest() {
        return RankingRequest.ofAvailableActions(
                "exampleId",
                null,
                List.of(
                        AvailableAction.of("sku-b", "b", ImmutableMap.of("request", "request-b")),
                        AvailableAction.of("sku-a", "a", ImmutableMap.of("request", "request-a"))
                )
        );
    }
}
