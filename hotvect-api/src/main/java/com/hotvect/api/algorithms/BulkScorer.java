package com.hotvect.api.algorithms;

import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.hotvect.utils.AdditionalProperties.getAdditionalProperties;

public interface BulkScorer<SHARED, ACTION> extends Function<RankingRequest<SHARED, ACTION>, DoubleList>, Algorithm {

    @Deprecated(forRemoval = true)
    @Override
    default DoubleList apply(RankingRequest<SHARED, ACTION> rankingRequest){
        // This should not be called - legacy implementations should override this method
        throw new UnsupportedOperationException("Legacy apply method should be overridden by implementations");
    }

    /**
     * Bulk score actions for the provided request.
     *
     * @deprecated Prefer {@link #score(RankingRequest)} which can also carry feature-store responses and
     * additional properties.
     */
    @Deprecated(forRemoval = true)
    default List<ScoringDecision<ACTION>> bulkScore(RankingRequest<SHARED, ACTION> rankingRequest) {
        // Default implementation for backward compatibility: call the legacy apply method
        DoubleList scores = apply(rankingRequest);
        List<ScoringDecision<ACTION>> decisions = new ArrayList<>(scores.size());
        List<ACTION> actions = rankingRequest.availableActions();
        
        for (int i = 0; i < scores.size() && i < actions.size(); i++) {
            ACTION action = actions.get(i);
            decisions.add(ScoringDecision.of(action, scores.getDouble(i), getAdditionalProperties(action)));
        }
        return decisions;
    }

    default BulkScoreResponse<ACTION> score(RankingRequest<SHARED, ACTION> rankingRequest) {
        return BulkScoreResponse.of(bulkScore(rankingRequest), FeatureStoreResponseContainer.empty());
    }
}
