package com.hotvect.core.rank;

import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hotvect.utils.AdditionalProperties.mergeAdditionalProperties;

/**
 * Utility class to convert a ranker into a bulk scorer.
 * This is necessary because we haven't started using {@link BulkScorer} yet.
 * @param <SHARED>
 * @param <ACTION>
 */
public class RankerToBulkScorer<SHARED, ACTION> implements BulkScorer<SHARED, ACTION> {
    private final Ranker<SHARED, ACTION> ranker;
    public RankerToBulkScorer(Ranker<SHARED, ACTION> ranker) {
        this.ranker = ranker;
    }

    @Override
    public BulkScoreResponse<ACTION> score(RankingRequest<SHARED, ACTION> rankingRequest) {
        RankingResponse<ACTION> rankingResponse = this.ranker.rank(rankingRequest);
        List<RankingDecision<ACTION>> scoredAndRanked = new ArrayList<>(rankingResponse.decisions());

        Map<String, Integer> actionIndexById = actionIndexById(rankingRequest);
        var actions = rankingRequest.actions();
        double[] scores = new double[actions.size()];
        boolean[] seen = new boolean[scores.length];
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object>[] decisionAdditionalProperties = new java.util.Map[scores.length];
        for (RankingDecision<ACTION> rankingDecision : scoredAndRanked) {
            int actionIdx = actionIndexFor(actionIndexById, rankingDecision);
            requireUnseenAction(seen, actionIdx, rankingDecision);
            scores[actionIdx] = rankingDecision.score();
            decisionAdditionalProperties[actionIdx] = rankingDecision.additionalProperties();
        }
        requireCompleteRanking(seen, scoredAndRanked.size(), rankingRequest);

        List<ScoringDecision<ACTION>> decisions = new ArrayList<>(scores.length);
        for (int i = 0; i < scores.length; i++) {
            decisions.add(ScoringDecision.of(
                    actions.get(i).actionId(),
                    actions.get(i).action(),
                    scores[i],
                    mergeAdditionalProperties(actions.get(i).additionalProperties(), decisionAdditionalProperties[i])
            ));
        }

        return BulkScoreResponse.of(
                decisions,
                rankingResponse.featureStoreResponseContainer(),
                rankingResponse.additionalProperties()
        );
    }

    @Override
    public DoubleList apply(RankingRequest<SHARED, ACTION> rankingRequest) {
        List<RankingDecision<ACTION>> scoredAndRanked = new ArrayList<>(this.ranker.rank(rankingRequest).decisions());
        Map<String, Integer> actionIndexById = actionIndexById(rankingRequest);
        double[] scores = new double[rankingRequest.actions().size()];
        boolean[] seen = new boolean[scores.length];
        for (RankingDecision<ACTION> rankingDecision : scoredAndRanked) {
            int actionIdx = actionIndexFor(actionIndexById, rankingDecision);
            requireUnseenAction(seen, actionIdx, rankingDecision);
            scores[actionIdx] = rankingDecision.score();
        }
        requireCompleteRanking(seen, scoredAndRanked.size(), rankingRequest);
        return DoubleArrayList.wrap(scores);
    }

    @Override
    public List<ScoringDecision<ACTION>> bulkScore(RankingRequest<SHARED, ACTION> rankingRequest) {
        // Preserve decision additionalProperties (e.g., feature-audit data) even on the legacy API.
        return score(rankingRequest).decisions();
    }

    private Map<String, Integer> actionIndexById(RankingRequest<SHARED, ACTION> rankingRequest) {
        var actions = rankingRequest.actions();
        Map<String, Integer> ret = new HashMap<>(actions.size());
        for (int i = 0; i < actions.size(); i++) {
            ret.put(actions.get(i).actionId(), i);
        }
        return ret;
    }

    private int actionIndexFor(Map<String, Integer> actionIndexById, RankingDecision<ACTION> rankingDecision) {
        Integer actionIndex = actionIndexById.get(rankingDecision.actionId());
        if (actionIndex == null) {
            throw new IllegalArgumentException("Ranker returned decision for unknown action id: " + rankingDecision.actionId());
        }
        return actionIndex;
    }

    private void requireUnseenAction(boolean[] seen, int actionIndex, RankingDecision<ACTION> rankingDecision) {
        if (seen[actionIndex]) {
            throw new IllegalArgumentException("Ranker returned duplicate decision for action id: " + rankingDecision.actionId());
        }
        seen[actionIndex] = true;
    }

    private void requireCompleteRanking(boolean[] seen, int actual, RankingRequest<SHARED, ACTION> rankingRequest) {
        int expected = rankingRequest.actions().size();
        if (actual != expected) {
            throw new IllegalArgumentException("Ranker returned " + actual + " decisions for " + expected + " actions");
        }
        var actions = rankingRequest.actions();
        for (int i = 0; i < seen.length; i++) {
            if (!seen[i]) {
                throw new IllegalArgumentException(
                        "Ranker did not return a decision for action id: " + actions.get(i).actionId()
                );
            }
        }
    }
}
