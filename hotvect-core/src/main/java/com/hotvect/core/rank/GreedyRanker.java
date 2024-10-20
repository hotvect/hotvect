package com.hotvect.core.rank;

import com.hotvect.api.algodefinition.ranking.RankingVectorizer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.core.score.Estimator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class GreedyRanker<SHARED, ACTION> implements Ranker<SHARED, ACTION> {
    private final RankingVectorizer<SHARED, ACTION> actionVectorizer;
    private final Estimator model;
    private final Comparator<IndexedScoredAction> COMPARATOR = (o1, o2) -> {
        // By score desc
        int byScore = Double.compare(o2.score, o1.score);
        if (byScore == 0) {
            return Integer.compare(o1.featureVector.hashCode(), o2.featureVector.hashCode());
        } else {
            return byScore;
        }
    };

    public GreedyRanker(RankingVectorizer<SHARED, ACTION> vectorizer, Estimator model) {
        this.actionVectorizer = vectorizer;
        this.model = model;
    }

    @Override
    public RankingResponse<ACTION> rank(RankingRequest<SHARED, ACTION> request) {
        var numActions = request.getAvailableActions().size();
        var vectorizedActions = actionVectorizer.apply(request);

        List<IndexedScoredAction> processed = new ArrayList<>(numActions);
        for (int i = 0; i < numActions; i++) {
            processed.add(
                    new IndexedScoredAction(
                            i,
                            request.getAvailableActions().get(i),
                            vectorizedActions.get(i),
                            model.applyAsDouble(vectorizedActions.get(i))
                    )
            );
        }

        processed.sort(COMPARATOR);

        List<RankingDecision<ACTION>> ret = new ArrayList<>(numActions);
        for (IndexedScoredAction action : processed) {
            ret.add(RankingDecision.builder(action.index, action.action).withScore(action.score).build());
        }

        return RankingResponse.newResponse(ret);
    }

    private class IndexedScoredAction {
        final int index;
        final ACTION action;
        final SparseVector featureVector;
        final double score;

        private IndexedScoredAction(int index, ACTION action, SparseVector featureVector, double score) {
            this.index = index;
            this.action = action;
            this.featureVector = featureVector;
            this.score = score;
        }
    }
}
