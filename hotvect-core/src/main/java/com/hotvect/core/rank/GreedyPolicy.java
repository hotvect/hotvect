package com.hotvect.core.rank;

import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.vectorization.RankingVectorizer;
import com.hotvect.core.score.Estimator;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class GreedyPolicy<SHARED, ACTION> implements Ranker<SHARED, ACTION> {
    private final RankingVectorizer<SHARED, ACTION> actionVectorizer;
    private final Estimator model;

    public GreedyPolicy(RankingVectorizer<SHARED, ACTION> vectorizer, Estimator model) {
        this.actionVectorizer = vectorizer;
        this.model = model;
    }

    @Override
    public List<RankingDecision> apply(RankingRequest<SHARED, ACTION> request) {
        var numActions = request.getAvailableActions().size();
        var vectorizedActions = actionVectorizer.apply(request);
        Int2DoubleMap actionIdx2Score = new Int2DoubleOpenHashMap(request.getAvailableActions().size());
        for (int i = 0; i < numActions; i++) {
            actionIdx2Score.put(i, model.applyAsDouble(vectorizedActions.get(i)));
        }

        int[] actionIdxOrderedByScore = actionIdx2Score.int2DoubleEntrySet().stream()
                .sorted(Comparator.comparingDouble(Int2DoubleMap.Entry::getDoubleValue).reversed())
                .mapToInt(Int2DoubleMap.Entry::getIntKey)
                .toArray();

        List<RankingDecision> ret = new ArrayList<>(numActions);
        for (int i = 0; i < actionIdxOrderedByScore.length; i++) {
            var actionIdx = actionIdxOrderedByScore[i];
            ret.add(new RankingDecision(actionIdx, actionIdx2Score.get(actionIdx)));
        }

        return ret;
    }
}
