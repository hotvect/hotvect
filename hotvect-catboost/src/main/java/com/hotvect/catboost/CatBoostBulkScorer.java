package com.hotvect.catboost;

import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.scoring.BulkScoreResponse;
import com.hotvect.api.data.scoring.ScoringDecision;
import com.hotvect.core.transform.ranking.ComputingBulkScorer;
import com.hotvect.core.transform.ranking.ComputingCandidate;
import com.hotvect.core.transform.ranking.ComputingRankingRequest;
import com.hotvect.core.transform.ranking.ComputingRankingTransformer;
import com.hotvect.onlineutils.concurrency.CommonPool;
import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RecursiveTask;
import java.util.function.Function;

public class CatBoostBulkScorer<SHARED, ACTION> implements ComputingBulkScorer<SHARED, ACTION> {
    private final ComputingRankingTransformer<SHARED, ACTION> transformer;
    private final CatBoostTransformedActionScorer<ACTION> transformedActionScorer;
    private final int noForkThreshold;
    private final Function<ComputingRankingRequest<SHARED, ACTION>, FeatureStoreResponseContainer> featureStoreResponseContainerProvider;

    public CatBoostBulkScorer(
            ComputingRankingTransformer<SHARED, ACTION> transformer,
            HotvectCatBoostModel hotvectCatBoostModel,
            int noForkThreshold,
            String taskType
    ) {
        this(
                transformer,
                hotvectCatBoostModel,
                noForkThreshold,
                taskType,
                _request -> FeatureStoreResponseContainer.empty()
        );
    }

    public CatBoostBulkScorer(
            ComputingRankingTransformer<SHARED, ACTION> transformer,
            HotvectCatBoostModel hotvectCatBoostModel,
            int noForkThreshold,
            String taskType,
            Function<ComputingRankingRequest<SHARED, ACTION>, FeatureStoreResponseContainer> featureStoreResponseContainerProvider
    ) {
        this.noForkThreshold = noForkThreshold;
        this.transformer = Objects.requireNonNull(transformer, "transformer");
        this.transformedActionScorer = new CatBoostTransformedActionScorer<>(
                transformer.getUsedFeatures(),
                hotvectCatBoostModel,
                taskType
        );
        this.featureStoreResponseContainerProvider = Objects.requireNonNull(
                featureStoreResponseContainerProvider,
                "featureStoreResponseContainerProvider"
        );
    }

    @Override
    public BulkScoreResponse<ACTION> score(RankingRequest<SHARED, ACTION> rankingRequest) {
        return score(transformer.prepare(rankingRequest));
    }

    @Override
    @Deprecated(forRemoval = true)
    public List<ScoringDecision<ACTION>> bulkScore(RankingRequest<SHARED, ACTION> rankingRequest) {
        return score(rankingRequest).decisions();
    }

    @Override
    public BulkScoreResponse<ACTION> score(ComputingRankingRequest<SHARED, ACTION> rankingRequest) {
        ComputingRankingRequest<SHARED, ACTION> preparedRankingRequest = transformer.prepare(rankingRequest);
        FeatureStoreResponseContainer featureStoreResponseContainer = featureStoreResponseContainerProvider.apply(
                preparedRankingRequest
        );
        return BulkScoreResponse.of(
                doApply(preparedRankingRequest),
                featureStoreResponseContainer
        );
    }

    @Override
    @Deprecated(forRemoval = true)
    public List<ScoringDecision<ACTION>> bulkScore(ComputingRankingRequest<SHARED, ACTION> rankingRequest) {
        return score(rankingRequest).decisions();
    }


    public List<ScoringDecision<ACTION>> doApply(ComputingRankingRequest<SHARED, ACTION> rankingRequest) {
        if (rankingRequest.candidates().size() <= noForkThreshold || noForkThreshold <= 0) {
            return process(rankingRequest);
        } else {
            return CommonPool.commonForkJoinPool().invoke(new RecursiveScoringTask(rankingRequest));
        }
    }




        private class RecursiveScoringTask extends RecursiveTask<List<ScoringDecision<ACTION>>> {
        private final ComputingRankingRequest<SHARED, ACTION> request;

        private RecursiveScoringTask(ComputingRankingRequest<SHARED, ACTION> request) {
            this.request = request;
        }

        @Override
        protected List<ScoringDecision<ACTION>> compute() {
            if(request.candidates().size() <= noForkThreshold || noForkThreshold <= 0){
                return process(request);
            } else {
                List<ComputingCandidate<SHARED,ACTION>> actions = request.candidates();
                int mid = actions.size() / 2;
                var secondTask = new RecursiveScoringTask(
                        new ComputingRankingRequest<>(
                                request.rankingRequest(),
                                request.shared(),
                                actions.subList(mid, actions.size())
                        )
                );
                var secondResult = secondTask.fork();
                var firstTask = new RecursiveScoringTask(
                        new ComputingRankingRequest<>(
                                request.rankingRequest(),
                                request.shared(),
                                actions.subList(0, mid)
                        )
                );
                var firstResult = firstTask.compute();
                firstResult.addAll(secondResult.join());
                return firstResult;
            }

        }
    }

    private List<ScoringDecision<ACTION>> process(ComputingRankingRequest<SHARED, ACTION> rankingRequest) {
        return transformedActionScorer.scoreTransformed(transformer.transform(rankingRequest));
    }

    @Override
    public void close() throws Exception {
        this.transformedActionScorer.close();
    }
}
