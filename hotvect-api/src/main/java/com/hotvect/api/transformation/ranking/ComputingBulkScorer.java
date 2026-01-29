package com.hotvect.api.transformation.ranking;

import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.scoring.ScoringDecision;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.ArrayList;
import java.util.List;

@Deprecated(forRemoval = true)
public interface ComputingBulkScorer<SHARED, ACTION> extends BulkScorer<SHARED, ACTION> {
    @Deprecated(forRemoval = true)
    @Override
    default DoubleList apply(RankingRequest<SHARED, ACTION> rankingRequest){
        throw new UnsupportedOperationException();
    }

    @Deprecated(forRemoval = true)
    default DoubleList apply(ComputingRankingRequest<SHARED, ACTION> rankingRequest){
        throw new UnsupportedOperationException();
    }

    default List<ScoringDecision<ACTION>> bulkScore(ComputingRankingRequest<SHARED, ACTION> rankingRequest){
        DoubleList scores = apply(rankingRequest);
        List<ScoringDecision<ACTION>> ret = new ArrayList<>(scores.size());
        for (int i = 0; i < scores.size(); i++) {
            ret.add(ScoringDecision.of(rankingRequest.rankingRequest().availableActions().get(i), scores.getDouble(i)));
        }
        return ret;
    }


}
