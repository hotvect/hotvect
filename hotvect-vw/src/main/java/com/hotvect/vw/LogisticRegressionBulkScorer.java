package com.hotvect.vw;

import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.scoring.ScoringDecision;
import com.hotvect.api.transformation.ComputingCandidate;
import com.hotvect.api.transformation.ranking.ComputingBulkScorer;
import com.hotvect.api.transformation.ranking.ComputingRankingRequest;
import com.hotvect.api.transformation.ranking.ComputingRankingVectorizer;
import com.hotvect.onlineutils.concurrency.CommonPool;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RecursiveTask;

public class LogisticRegressionBulkScorer<SHARED, ACTION> implements ComputingBulkScorer<SHARED, ACTION> {
    private final ComputingRankingVectorizer<SHARED, ACTION> vectorizer;
    private final LogisticRegressionEstimator logisticRegressionEstimator;
    private final int noForkThreshold;

    public LogisticRegressionBulkScorer(
            ComputingRankingVectorizer<SHARED, ACTION> vectorizer,
            LogisticRegressionEstimator logisticRegressionEstimator,
            int noForkThreshold
    ) {
        this.vectorizer = vectorizer;
        this.logisticRegressionEstimator = logisticRegressionEstimator;
        this.noForkThreshold = noForkThreshold;
    }

    @Override
    public List<ScoringDecision<ACTION>> bulkScore(RankingRequest<SHARED, ACTION> rankingRequest) {
        DoubleList scores = apply(rankingRequest);
        List<ScoringDecision<ACTION>> ret = new ArrayList<>(scores.size());
        for (int i = 0; i < scores.size(); i++) {
            ret.add(ScoringDecision.of(rankingRequest.availableActions().get(i), scores.getDouble(i)));
        }
        return ret;
    }

    @Override
    public DoubleList apply(RankingRequest<SHARED, ACTION> rankingRequest) {
        ComputingRankingRequest<SHARED, ACTION> memoized = vectorizer.prepare(rankingRequest);
        return this.apply(memoized);
    }

    @Override
    public DoubleList apply(ComputingRankingRequest<SHARED, ACTION> rankingRequest) {

        if (rankingRequest.candidates().size() <= noForkThreshold || noForkThreshold <= 0) {
            return doApply(rankingRequest);
        } else {
            return CommonPool.forkJoinPoolWithSlack().invoke(new RecursiveScoringTask(rankingRequest));
        }
    }
    private class RecursiveScoringTask extends RecursiveTask<DoubleList> {
        private final ComputingRankingRequest<SHARED, ACTION> request;

        private RecursiveScoringTask(ComputingRankingRequest<SHARED, ACTION> request) {
            this.request = request;
        }

        @Override
        protected DoubleList compute() {
            if(request.candidates().size() <= noForkThreshold){
                return doApply(request);
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

    private DoubleList doApply(ComputingRankingRequest<SHARED, ACTION> rankingRequest) {
        var actions = rankingRequest.candidates();
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
