package com.hotvect.core.rank;

import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.hotvect.utils.AdditionalProperties.mergeAdditionalProperties;

public class BulkScoreGreedyRanker<SHARED, ACTION> implements Ranker<SHARED, ACTION> {
    private final BulkScorer<SHARED, ACTION> bulkScorer;

    public BulkScoreGreedyRanker(BulkScorer<SHARED, ACTION> bulkScorer) {
        this.bulkScorer = bulkScorer;
    }

    @Override
    public RankingResponse<ACTION> rank(RankingRequest<SHARED, ACTION> request) {
        var actions = request.actions();
        int numActions = actions.size();
        BulkScoreResponse<ACTION> scoringResponse = this.bulkScorer.score(request);
        List<ScoringDecision<ACTION>> scores = scoringResponse.decisions();
        if (scores.size() != numActions) {
            throw new IllegalArgumentException(
                    "BulkScorer returned " + scores.size() + " scores for " + numActions + " actions"
            );
        }

        List<RankingDecision<ACTION>> decisions = new ArrayList<>(numActions);

        // O(n) ID checks are assertion-only; scorer order is part of the BulkScorer contract.
        assert validateScoreActionIds(request, scores);
        for (int i = 0; i < numActions; ++i) {
            String actionId = actions.get(i).actionId();
            ScoringDecision<ACTION> score = scores.get(i);
            decisions.add(new RankingDecision<>(
                    actionId,
                    i,
                    score.score(),
                    actions.get(i).action(),
                    null,
                    mergeAdditionalProperties(actions.get(i).additionalProperties(), score.additionalProperties())
            ));
        }

        RankingTieBreakers.sortDecisions(decisions, request.exampleId());
        return RankingResponse.newResponse(
                decisions,
                scoringResponse.featureStoreResponseContainer(),
                scoringResponse.additionalProperties()
        );
    }

    private static <SHARED, ACTION> boolean validateScoreActionIds(
            RankingRequest<SHARED, ACTION> request,
            List<ScoringDecision<ACTION>> scores
    ) {
        boolean hasActionIds = false;
        boolean hasPositionalScores = false;
        for (int i = 0; i < scores.size(); i++) {
            String scoreActionId = scores.get(i).actionId();
            if (scoreActionId == null) {
                hasPositionalScores = true;
            } else {
                hasActionIds = true;
                String expectedActionId = request.actions().get(i).actionId();
                checkArgument(
                        scoreActionId.equals(expectedActionId),
                        "BulkScorer must preserve request action order; score at position %s has action id %s, expected %s",
                        i,
                        scoreActionId,
                        expectedActionId
                );
            }
        }
        if (!hasActionIds) {
            return true;
        }
        checkArgument(
                !hasPositionalScores,
                "BulkScorer returned a mix of action-id and positional scores"
        );
        return true;
    }

    @Override
    public void close() throws Exception {
        bulkScorer.close();
    }
}
