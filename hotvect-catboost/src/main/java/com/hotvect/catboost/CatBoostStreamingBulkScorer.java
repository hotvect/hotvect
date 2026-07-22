package com.hotvect.catboost;

import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.featurestore.FeatureStoreResponse;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.core.transform.ranking.PreparedBatchStream;
import com.hotvect.core.transform.ranking.StreamingRankingTransformer;
import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.hotvect.utils.AdditionalProperties.mergeAdditionalProperties;

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
    private final boolean parallelBatchScoring;
    private final Function<Map<String, FeatureStoreResponse>, FeatureStoreResponseContainer> featureStoreResponseContainerProvider;

    public CatBoostStreamingBulkScorer(
            StreamingRankingTransformer<SHARED, ACTION> transformer,
            HotvectCatBoostModel hotvectCatBoostModel,
            String taskType
    ) {
        this(transformer, hotvectCatBoostModel, taskType, true);
    }

    public CatBoostStreamingBulkScorer(
            StreamingRankingTransformer<SHARED, ACTION> transformer,
            HotvectCatBoostModel hotvectCatBoostModel,
            String taskType,
            boolean parallelBatchScoring
    ) {
        this(transformer, hotvectCatBoostModel, taskType, parallelBatchScoring, _responses -> FeatureStoreResponseContainer.empty());
    }

    public CatBoostStreamingBulkScorer(
            StreamingRankingTransformer<SHARED, ACTION> transformer,
            HotvectCatBoostModel hotvectCatBoostModel,
            String taskType,
            Function<Map<String, FeatureStoreResponse>, FeatureStoreResponseContainer> featureStoreResponseContainerProvider
    ) {
        this(transformer, hotvectCatBoostModel, taskType, true, featureStoreResponseContainerProvider);
    }

    public CatBoostStreamingBulkScorer(
            StreamingRankingTransformer<SHARED, ACTION> transformer,
            HotvectCatBoostModel hotvectCatBoostModel,
            String taskType,
            boolean parallelBatchScoring,
            Function<Map<String, FeatureStoreResponse>, FeatureStoreResponseContainer> featureStoreResponseContainerProvider
    ) {
        this.transformer = transformer;
        this.transformedActionScorer = new CatBoostTransformedActionScorer<>(
                transformer.getUsedFeatures(),
                hotvectCatBoostModel,
                taskType
        );
        this.parallelBatchScoring = parallelBatchScoring;
        this.featureStoreResponseContainerProvider = Objects.requireNonNull(
                featureStoreResponseContainerProvider,
                "featureStoreResponseContainerProvider cannot be null"
        );
    }

    @Override
    public BulkScoreResponse<ACTION> score(RankingRequest<SHARED, ACTION> rankingRequest) {
        if (rankingRequest.actions().isEmpty()) {
            return BulkScoreResponse.of(Collections.emptyList(), FeatureStoreResponseContainer.empty());
        }

        PreparedBatchStream<ACTION> prepared = transformer.prepareBatchStream(rankingRequest);
        List<ScoringDecision<ACTION>> decisions = (parallelBatchScoring
                ? prepared.batchStream().parallel()
                : prepared.batchStream().sequential())
                .map(transformedActionScorer::scoreTransformed)
                .flatMap(List::stream)
                .toList();
        decisions = mergeRequestAdditionalProperties(decisions, rankingRequest.actions());
        FeatureStoreResponseContainer container = featureStoreResponseContainerProvider.apply(prepared.featureStoreResponses());
        return BulkScoreResponse.of(decisions, container);
    }

    @Override
    @Deprecated(forRemoval = true)
    public List<ScoringDecision<ACTION>> bulkScore(RankingRequest<SHARED, ACTION> rankingRequest) {
        return score(rankingRequest).decisions();
    }

    @Override
    public void close() throws Exception {
        this.transformedActionScorer.close();
    }

    private List<ScoringDecision<ACTION>> mergeRequestAdditionalProperties(
            List<ScoringDecision<ACTION>> decisions,
            List<AvailableAction<ACTION>> actions
    ) {
        checkArgument(
                decisions.size() == actions.size(),
                "CatBoost scorer returned %s decisions for %s actions",
                decisions.size(),
                actions.size()
        );

        List<ScoringDecision<ACTION>> ret = new ArrayList<>(decisions.size());
        for (int i = 0; i < decisions.size(); i++) {
            ScoringDecision<ACTION> decision = decisions.get(i);
            AvailableAction<ACTION> action = actions.get(i);
            checkArgument(
                    decision.actionId().equals(action.actionId()),
                    "CatBoost scorer returned action id %s at position %s, expected %s",
                    decision.actionId(),
                    i,
                    action.actionId()
            );
            ret.add(ScoringDecision.of(
                    decision.actionId(),
                    decision.action(),
                    decision.score(),
                    mergeAdditionalProperties(action.additionalProperties(), decision.additionalProperties())
            ));
        }
        return ret;
    }
}
