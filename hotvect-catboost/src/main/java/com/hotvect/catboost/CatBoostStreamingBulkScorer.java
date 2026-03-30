package com.hotvect.catboost;

import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.scoring.ScoringDecision;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.core.transform.ranking.StreamingRankingTransformer;
import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;

import java.util.Collections;
import java.util.List;

/**
 * Bulk CatBoost scorer that uses a {@link StreamingRankingTransformer}.
 *
 * <p>Feature transformation happens before inference, but is executed as a single chained pipeline:
 * transform batches → encode → model inference.</p>
 *
 * <p>Batching is lazy: batches are formed as the stream is consumed, so scoring can begin on early
 * batches while later batches are still being transformed.</p>
 */
public class CatBoostStreamingBulkScorer<SHARED, ACTION> implements BulkScorer<SHARED, ACTION> {
    private final StreamingRankingTransformer<SHARED, ACTION> transformer;
    private final CatBoostTransformedActionScorer<ACTION> transformedActionScorer;

    public CatBoostStreamingBulkScorer(
            StreamingRankingTransformer<SHARED, ACTION> transformer,
            HotvectCatBoostModel hotvectCatBoostModel,
            String taskType
    ) {
        this.transformer = transformer;
        this.transformedActionScorer = new CatBoostTransformedActionScorer<>(
                transformer.getUsedFeatures(),
                hotvectCatBoostModel,
                taskType
        );
    }

    @Override
    public List<ScoringDecision<ACTION>> bulkScore(RankingRequest<SHARED, ACTION> rankingRequest) {
        if (rankingRequest.availableActions().isEmpty()) return Collections.emptyList();

        // Chained pipeline (default ForkJoinPool.commonPool()):
        // transform stream -> batch lazily -> score batches in parallel -> flatten
        return transformer.transformBatchStream(rankingRequest)
                .parallel()
                .map(transformedActionScorer::scoreTransformed)
                .flatMap(List::stream)
                .toList();
    }

    @Override
    public void close() throws Exception {
        this.transformedActionScorer.close();
    }
}
