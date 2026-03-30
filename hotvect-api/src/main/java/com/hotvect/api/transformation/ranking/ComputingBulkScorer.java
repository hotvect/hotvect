package com.hotvect.api.transformation.ranking;

import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.ArrayList;
import java.util.List;

import static com.hotvect.utils.AdditionalProperties.getAdditionalProperties;

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
        List<ScoringDecision<ACTION>> decisions = new ArrayList<>(scores.size());
        List<ACTION> actions = rankingRequest.rankingRequest().availableActions();

        for (int i = 0; i < scores.size() && i < actions.size(); i++) {
            ACTION action = actions.get(i);
            decisions.add(ScoringDecision.of(action, scores.getDouble(i), getAdditionalProperties(action)));
        }
        return decisions;
    }

    default BulkScoreResponse<ACTION> score(ComputingRankingRequest<SHARED, ACTION> rankingRequest) {
        return BulkScoreResponse.of(
                bulkScore(rankingRequest),
                FeatureStoreResponseContainer.empty()
        );
    }

}
