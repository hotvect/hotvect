package com.hotvect.core.rank;

import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;
import com.hotvect.api.data.featurestore.SimpleFeatureStoreResponse;
import com.google.common.collect.ImmutableMap;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BulkScoreGreedyRankerTest {

    @Property
    boolean testRankingBehavior(@ForAll @Size(min = 1, max = 20) List<String> actions,
                                @ForAll List<Double> scores) {
        Assume.that(scores.size() >= actions.size());

        RankingRequest<Void, String> request = new RankingRequest<>("exampleId", (Void) null, actions);
        FeatureStoreResponseContainer container = new FeatureStoreResponseContainer(
                ImmutableMap.of(
                        "view_1",
                        SimpleFeatureStoreResponse.builder().allEntities(ImmutableMap.of()).build()
                )
        );
        Map<String, Object> responseAdditionalProperties = ImmutableMap.of("response_prop", "1");
        BulkScorer<Void, String> scorer = new BulkScorer<>() {
            @Override
            public List<ScoringDecision<String>> bulkScore(RankingRequest<Void, String> rankingRequest) {
                List<ScoringDecision<String>> decisions = new ArrayList<>(rankingRequest.availableActions().size());
                for (int i = 0; i < rankingRequest.availableActions().size(); i++) {
                    decisions.add(ScoringDecision.of(rankingRequest.availableActions().get(i), scores.get(i)));
                }
                return decisions;
            }

            @Override
            public BulkScoreResponse<String> score(RankingRequest<Void, String> rankingRequest) {
                return BulkScoreResponse.of(
                        bulkScore(rankingRequest),
                        container,
                        responseAdditionalProperties
                );
            }
        };

        var ranker = new BulkScoreGreedyRanker<>(scorer);
        RankingResponse<String> response = ranker.rank(request);
        List<RankingDecision<String>> decisions = response.rankingDecisions();

        if (decisions.size() != actions.size()) {
            return false;
        }

        // Extract the scores from decisions
        List<Double> decisionScores = new ArrayList<>(decisions.size());
        for (RankingDecision<String> d : decisions) {
            decisionScores.add(d.score());
        }

        // Sort the original scores in descending order
        List<Double> sortedScores = new ArrayList<>(scores.subList(0, actions.size()));
        sortedScores.sort(Collections.reverseOrder());

        // Compare sortedScores to decisionScores to ensure correct ordering
        return decisionScores.equals(sortedScores)
                && response.featureStoreResponseContainer().equals(container)
                && response.additionalProperties().equals(responseAdditionalProperties);
    }

    @Test
    void closeDelegatesToBulkScorer() throws Exception {
        AtomicInteger closeCalls = new AtomicInteger(0);
        BulkScorer<Void, String> scorer = new BulkScorer<>() {
            @Override
            public List<ScoringDecision<String>> bulkScore(RankingRequest<Void, String> rankingRequest) {
                return List.of();
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
            }
        };

        var ranker = new BulkScoreGreedyRanker<>(scorer);
        ranker.close();
        assertEquals(1, closeCalls.get());
    }

    @Test
    void preservesActionIdFromActionModel() {
        record ActionWithId(String id) {
            public String getId() {
                return id;
            }
        }

        RankingRequest<Void, ActionWithId> request = new RankingRequest<>(
                "exampleId",
                null,
                List.of(new ActionWithId("sku-b"), new ActionWithId("sku-a"))
        );
        BulkScorer<Void, ActionWithId> scorer = new BulkScorer<>() {
            @Override
            public List<ScoringDecision<ActionWithId>> bulkScore(RankingRequest<Void, ActionWithId> rankingRequest) {
                return List.of(
                        ScoringDecision.of(rankingRequest.availableActions().get(0), 0.1),
                        ScoringDecision.of(rankingRequest.availableActions().get(1), 0.9)
                );
            }
        };

        var ranker = new BulkScoreGreedyRanker<>(scorer);
        RankingResponse<ActionWithId> response = ranker.rank(request);

        assertEquals("sku-a", response.rankingDecisions().get(0).actionId());
        assertEquals("sku-b", response.rankingDecisions().get(1).actionId());
    }
}
