package com.hotvect.api.algorithms;

import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

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
     * <p>The returned decisions must preserve {@link RankingRequest#actions()} order: one score per
     * request action, at the same index. Rankers may reorder candidates after scoring.</p>
     *
     * @deprecated Prefer {@link #score(RankingRequest)} which can also carry feature-store responses and
     * additional properties.
     */
    @Deprecated(forRemoval = true)
    default List<ScoringDecision<ACTION>> bulkScore(RankingRequest<SHARED, ACTION> rankingRequest) {
        // Default implementation for backward compatibility: call the legacy apply method
        DoubleList scores = apply(rankingRequest);
        List<ScoringDecision<ACTION>> decisions = new ArrayList<>(scores.size());
        var actions = rankingRequest.actions();
        if (scores.size() != actions.size()) {
            throw new IllegalArgumentException(
                    "BulkScorer returned " + scores.size() + " scores for " + actions.size() + " actions"
            );
        }

        for (int i = 0; i < scores.size(); i++) {
            var action = actions.get(i);
            decisions.add(ScoringDecision.of(
                    action.actionId(),
                    action.action(),
                    scores.getDouble(i),
                    action.additionalProperties()
            ));
        }
        return decisions;
    }

    default BulkScoreResponse<ACTION> score(RankingRequest<SHARED, ACTION> rankingRequest) {
        return BulkScoreResponse.of(bulkScore(rankingRequest), FeatureStoreResponseContainer.empty());
    }
}
