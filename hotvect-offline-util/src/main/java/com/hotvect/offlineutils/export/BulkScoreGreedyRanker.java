package com.hotvect.offlineutils.export;

import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;
import com.hotvect.utils.ListTransform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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
        int numActions = request.availableActions().size();
        BulkScoreResponse<ACTION> scoringResponse = this.bulkScorer.score(request);
        List<ScoringDecision<ACTION>> scoringDecisions = scoringResponse.decisions();


        List<IndexedScoredAction> processed = new ArrayList<>(numActions);

        for(int i = 0; i < numActions; ++i) {
            processed.add(new IndexedScoredAction(i, request.availableActions().get(i), scoringDecisions.get(i).score(), scoringDecisions.get(i).additionalProperties()));
        }

        processed.sort(this.COMPARATOR);
        var decisions = ListTransform.map(processed, x -> RankingDecision.builder(x.index, x.action).withScore(x.score).withAdditionalProperties(x.additionalProperties).build());
        return RankingResponse.newResponse(
                decisions,
                scoringResponse.featureStoreResponseContainer(),
                scoringResponse.additionalProperties()
        );
    }

    @Override
    public void close() throws Exception {
        bulkScorer.close();
    }

    private class IndexedScoredAction {
        final int index;
        final ACTION action;
        final double score;
        final Map<String, Object> additionalProperties;

        private IndexedScoredAction(int index, ACTION action, double score, Map<String, Object> additionalProperties) {
            this.index = index;
            this.action = action;
            this.score = score;
            this.additionalProperties = additionalProperties;
        }
    }
}
