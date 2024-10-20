package com.hotvect.offlineutils.export;

import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.utils.ListTransform;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * This class is a copy of the BulkScoreGreedyRanker class in the core module.
 * It is necessary to repeat it here to avoid having core module as a dependency of the offlineutils module.
 * @param <SHARED>
 * @param <ACTION>
 */
public class BulkScoreGreedyRanker<SHARED, ACTION> implements Ranker<SHARED, ACTION> {
    private final BulkScorer<SHARED, ACTION> bulkScorer;
    private final Comparator<IndexedScoredAction> COMPARATOR = (o1, o2) -> {
        int byScore = Double.compare(o2.score, o1.score);
        // This tie breaking is not very nice, because it's not guaranteed that it's stable across
        // invocation. But for now we ignore it TODO
        return byScore == 0 ? Integer.compare(o1.hashCode(), o2.hashCode()) : byScore;
    };

    public BulkScoreGreedyRanker(BulkScorer<SHARED, ACTION> bulkScorer) {
        this.bulkScorer = bulkScorer;
    }

    @Override
    public RankingResponse<ACTION> rank(RankingRequest<SHARED, ACTION> request) {
        int numActions = request.getAvailableActions().size();
        DoubleList scores = this.bulkScorer.apply(request);


        List<IndexedScoredAction> processed = new ArrayList<>(numActions);

        for(int i = 0; i < numActions; ++i) {
            processed.add(new IndexedScoredAction(i, request.getAvailableActions().get(i), scores.getDouble(i)));
        }

        processed.sort(this.COMPARATOR);
        var decisions = ListTransform.map(processed, x -> RankingDecision.builder(x.index, x.action).withScore(x.score).build());
        return RankingResponse.newResponse(decisions);
    }

    private class IndexedScoredAction {
        final int index;
        final ACTION action;
        final double score;

        private IndexedScoredAction(int index, ACTION action, double score) {
            this.index = index;
            this.action = action;
            this.score = score;
        }
    }
}
