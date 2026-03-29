package com.hotvect.core.rank;

import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.featurestore.SimpleFeatureStoreResponse;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BulkScoreGreedyRankerTest {

    @Property
    boolean testRankingBehavior(@ForAll @Size(min = 1, max = 20) List<String> actions,
                                @ForAll List<Double> scores) {
        Assume.that(scores.size() >= actions.size());

        RankingRequest<Void, String> request = new RankingRequest<>("exampleId", (Void) null, actions);
        FeatureStoreResponseContainer featureStoreResponseContainer = new FeatureStoreResponseContainer(
                java.util.Map.of("view", SimpleFeatureStoreResponse.success(java.util.Map.of()))
        );
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
                        featureStoreResponseContainer,
                        java.util.Map.of("algo", "v77")
                );
            }
        };

        var ranker = new BulkScoreGreedyRanker<>(scorer);
        RankingResponse<String> response = ranker.rank(request);
        if (!featureStoreResponseContainer.equals(response.featureStoreResponseContainer())) {
            return false;
        }
        if (!java.util.Map.of("algo", "v77").equals(response.additionalProperties())) {
            return false;
        }
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
        return decisionScores.equals(sortedScores);
    }
}
