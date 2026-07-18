package com.hotvect.core.rank;

import com.hotvect.api.algodefinition.ranking.RankingVectorizer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.core.score.Estimator;

import java.util.ArrayList;
import java.util.List;

public class GreedyRanker<SHARED, ACTION> implements Ranker<SHARED, ACTION> {
    private final RankingVectorizer<SHARED, ACTION> actionVectorizer;
    private final Estimator model;

    public GreedyRanker(RankingVectorizer<SHARED, ACTION> vectorizer, Estimator model) {
        this.actionVectorizer = vectorizer;
        this.model = model;
    }

    @Override
    public RankingResponse<ACTION> rank(RankingRequest<SHARED, ACTION> request) {
        var actions = request.actions();
        var numActions = actions.size();
        var vectorizedActions = actionVectorizer.apply(request);
        if (vectorizedActions.size() != numActions) {
            throw new IllegalArgumentException(
                    "RankingVectorizer returned " + vectorizedActions.size() + " vectors for " + numActions + " actions"
            );
        }

        List<RankingDecision<ACTION>> decisions = new ArrayList<>(numActions);
        for (int i = 0; i < numActions; i++) {
            ACTION action = actions.get(i).action();
            String actionId = actions.get(i).actionId();
            decisions.add(
                    new RankingDecision<>(
                            actionId,
                            i,
                            model.applyAsDouble(vectorizedActions.get(i)),
                            action,
                            null,
                            actions.get(i).additionalProperties()
                    )
            );
        }

        RankingTieBreakers.sortDecisions(decisions, request.exampleId());

        return RankingResponse.newResponse(decisions);
    }
}
