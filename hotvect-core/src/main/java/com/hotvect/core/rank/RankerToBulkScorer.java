package com.hotvect.core.rank;

import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.scoring.ScoringDecision;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.ArrayList;
import java.util.List;

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
    public DoubleList apply(RankingRequest<SHARED, ACTION> rankingRequest) {
        List<RankingDecision<ACTION>> scoredAndRanked = new ArrayList<>(this.ranker.rank(rankingRequest).decisions());
        double[] scores = new double[scoredAndRanked.size()];
        for (RankingDecision<ACTION> rankingDecision : scoredAndRanked) {
            int actionIdx = rankingDecision.getActionIndex();
            scores[actionIdx] = rankingDecision.score();
        }
        return DoubleArrayList.wrap(scores);
    }

    @Override
    public List<ScoringDecision<ACTION>> bulkScore(RankingRequest<SHARED, ACTION> rankingRequest) {
        DoubleList scores = apply(rankingRequest);
        List<ScoringDecision<ACTION>> ret = new ArrayList<>(scores.size());
        for (int i = 0; i < scores.size(); i++) {
            ret.add(ScoringDecision.of(rankingRequest.availableActions().get(i), scores.getDouble(i)));
        }
        return ret;
    }
}
