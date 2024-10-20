package com.hotvect.vw;

import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.transformation.memoization.ComputingCandidate;
import com.hotvect.api.transformation.ranking.MemoizableBulkScorer;
import com.hotvect.api.transformation.ranking.MemoizableRankingVectorizer;
import com.hotvect.api.transformation.ranking.MemoizedRankingRequest;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

public class LogisticRegressionBulkScorer<SHARED, ACTION> implements MemoizableBulkScorer<SHARED, ACTION> {
    private final MemoizableRankingVectorizer<SHARED, ACTION> vectorizer;
    private final LogisticRegressionEstimator logisticRegressionEstimator;
    private final int noForkThreshold;

    public LogisticRegressionBulkScorer(
            MemoizableRankingVectorizer<SHARED, ACTION> vectorizer,
            LogisticRegressionEstimator logisticRegressionEstimator,
            int noForkThreshold
    ) {
        this.vectorizer = vectorizer;
        this.logisticRegressionEstimator = logisticRegressionEstimator;
        this.noForkThreshold = noForkThreshold;
    }

    @Override
    public DoubleList apply(RankingRequest<SHARED, ACTION> rankingRequest) {
        MemoizedRankingRequest<SHARED, ACTION> memoized = vectorizer.memoize(rankingRequest);
        return this.apply(memoized);
    }

    @Override
    public DoubleList apply(MemoizedRankingRequest<SHARED, ACTION> rankingRequest) {

        if (rankingRequest.getAction().size() <= noForkThreshold || noForkThreshold <= 0) {
            return doApply(rankingRequest);
        } else {
            return ForkJoinPool.commonPool().invoke(new RecursiveScoringTask(rankingRequest));
        }
    }
    private class RecursiveScoringTask extends RecursiveTask<DoubleList> {
        private final MemoizedRankingRequest<SHARED, ACTION> request;

        private RecursiveScoringTask(MemoizedRankingRequest<SHARED, ACTION> request) {
            this.request = request;
        }

        @Override
        protected DoubleList compute() {
            if(request.getAction().size() <= noForkThreshold){
                return doApply(request);
            } else {
                List<ComputingCandidate<SHARED,ACTION>> actions = request.getAction();
                int mid = actions.size() / 2;
                var secondTask = new RecursiveScoringTask(
                        new MemoizedRankingRequest<>(
                                request.getRankingRequest(),
                                request.getShared(),
                                actions.subList(mid, actions.size())
                        )
                );
                var secondResult = secondTask.fork();
                var firstTask = new RecursiveScoringTask(
                        new MemoizedRankingRequest<>(
                                request.getRankingRequest(),
                                request.getShared(),
                                actions.subList(0, mid)
                        )
                );
                var firstResult = firstTask.compute();
                firstResult.addAll(secondResult.join());
                return firstResult;
            }

        }
    }

    private DoubleList doApply(MemoizedRankingRequest<SHARED, ACTION> rankingRequest) {
        var actions = rankingRequest.getAction();
        DoubleList ret = new DoubleArrayList(actions.size());
        List<SparseVector> vectorized = vectorizer.apply(rankingRequest);
        for (int actionIdx = 0; actionIdx < actions.size(); actionIdx++) {
            var sparseVector = vectorized.get(actionIdx);
            double score = this.logisticRegressionEstimator.applyAsDouble(sparseVector);
            ret.add(score);
        }
        return ret;
    }

}
