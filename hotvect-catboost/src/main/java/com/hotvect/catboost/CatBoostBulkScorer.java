package com.hotvect.catboost;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.RawValue;
import com.hotvect.api.data.RawValueType;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.transformation.memoization.ComputingCandidate;
import com.hotvect.api.transformation.ranking.MemoizableBulkScorer;
import com.hotvect.api.transformation.ranking.MemoizableRankingTransformer;
import com.hotvect.api.transformation.ranking.MemoizedRankingRequest;
import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;
import static com.hotvect.utils.Utils.checkCollectionIsEnumsOrNamespaceIdObjects;

public class CatBoostBulkScorer<SHARED, ACTION> implements MemoizableBulkScorer<SHARED, ACTION> {
    private final MemoizableRankingTransformer<SHARED, ACTION> transformer;
    private final HotvectCatBoostModel hotvectCatBoostModel;

    private final List<FeatureNamespace> numericalFeatures;
    private final List<FeatureNamespace> categoricalFeatures;
    private final List<FeatureNamespace> textFeatures;
    private final List<FeatureNamespace> embdeddedFeatures;

    private final int noForkThreshold;
    private final TaskType taskType;

    public CatBoostBulkScorer(
            MemoizableRankingTransformer<SHARED, ACTION> transformer,
            HotvectCatBoostModel hotvectCatBoostModel,
            int noForkThreshold,
            String taskType
    ) {
        this.noForkThreshold = noForkThreshold;
        this.transformer = transformer;
        this.hotvectCatBoostModel = hotvectCatBoostModel;
        this.taskType = TaskType.fromString(taskType);


        checkCollectionIsEnumsOrNamespaceIdObjects(transformer.getUsedFeatures());

        Set<FeatureNamespace> featureNamespaces = transformer.getUsedFeatures();

        this.numericalFeatures = ImmutableList.copyOf(featureNamespaces.stream().filter(x -> x.getFeatureValueType() == CatBoostFeatureType.NUMERICAL).collect(Collectors.toList()));
        this.categoricalFeatures = ImmutableList.copyOf(featureNamespaces.stream().filter(x -> x.getFeatureValueType() == CatBoostFeatureType.CATEGORICAL).collect(Collectors.toList()));
        this.textFeatures = ImmutableList.copyOf(featureNamespaces.stream().filter(x -> x.getFeatureValueType() == CatBoostFeatureType.TEXT).collect(Collectors.toList()));
        this.embdeddedFeatures = ImmutableList.copyOf(featureNamespaces.stream().filter(x -> x.getFeatureValueType() == CatBoostFeatureType.EMBEDDING).collect(Collectors.toList()));
    }

    @Override
    public DoubleList apply(RankingRequest<SHARED, ACTION> rankingRequest) {
        MemoizedRankingRequest<SHARED, ACTION> memoized = transformer.memoize(rankingRequest);
        return this.apply(memoized);
    }

    @Override
    public DoubleList apply(MemoizedRankingRequest<SHARED, ACTION> rankingRequest) {
        if (rankingRequest.getAction().size()<=noForkThreshold){
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
            if(request.getAction().size() <= noForkThreshold || noForkThreshold <= 0){
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
        int actionSize = rankingRequest.getAction().size();
        List<NamespacedRecord<FeatureNamespace, RawValue>> transformed = transformer.apply(rankingRequest);
        float[][] numericals = new float[actionSize][CatBoostBulkScorer.this.numericalFeatures.size()];
        float[][][] embeddings = new float[actionSize][CatBoostBulkScorer.this.embdeddedFeatures.size()][];
        String[][] categoricals = new String[actionSize][CatBoostBulkScorer.this.categoricalFeatures.size()];
        String[][] texts = new String[actionSize][CatBoostBulkScorer.this.textFeatures.size()];
        for (int actionIdx = 0; actionIdx < actionSize; actionIdx++) {
            var dataRecord = transformed.get(actionIdx);

            processNumericals(dataRecord, numericals[actionIdx]);

            processCategorical(dataRecord, categoricals[actionIdx]);

            processText(dataRecord, texts[actionIdx]);

            processEmbedding(dataRecord, embeddings[actionIdx]);

        }
        DoubleList predictedScores = CatBoostBulkScorer.this.hotvectCatBoostModel.predict(numericals, categoricals, texts, embeddings);
        if (this.taskType == TaskType.CLASSIFICATION) {
            for (int i = 0; i < predictedScores.size(); i++) {
                predictedScores.set(i, sigmoid(predictedScores.get(i)));
            }
        }
        return predictedScores;
    }

    private void processEmbedding(NamespacedRecord<FeatureNamespace, RawValue> namespacedRecord, float[][] embeddings) {
        for (int featureIdx = 0; featureIdx < CatBoostBulkScorer.this.embdeddedFeatures.size(); featureIdx++) {
            FeatureNamespace feature = CatBoostBulkScorer.this.embdeddedFeatures.get(featureIdx);
            RawValue rawValue = namespacedRecord.get(feature);

            float[] featureValue;

            if (rawValue == null || rawValue.getNumericals().length == 0) {
                featureValue = new float[]{Float.NaN};
            } else {
                featureValue = new float[rawValue.getNumericals().length];

                for (int i = 0; i < featureValue.length; i++) {
                    featureValue[i] = (float) rawValue.getNumericals()[i];
                }
            }

            embeddings[featureIdx] = featureValue;
        }
    }

    private void processText(NamespacedRecord<FeatureNamespace, RawValue> namespacedRecord, String[] texts) {
        // text features
        for (int featureIdx = 0; featureIdx < CatBoostBulkScorer.this.textFeatures.size(); featureIdx++) {
            FeatureNamespace feature = CatBoostBulkScorer.this.textFeatures.get(featureIdx);
            RawValue rawValue = namespacedRecord.get(feature);
            String featureValue;
            if (rawValue == null || rawValue.getStrings().length == 0) {
                featureValue = CatBoostEncoder.MISSING_TEXT;
            } else {
                StringBuilder sb = new StringBuilder();
                for (String string : rawValue.getStrings()) {
                    checkState(!Strings.isNullOrEmpty(string), "Suspicious empty string:%s", rawValue);
                    checkState(!string.contains(" "), "Feature value may not contain spaces:%s", rawValue);
                    sb.append(string);
                    sb.append(" ");
                }
                // Remove excess space character
                sb.deleteCharAt(sb.length() - 1);
                featureValue = sb.toString();
            }
            texts[featureIdx] = featureValue;
        }
    }

    private void processCategorical(NamespacedRecord<FeatureNamespace, RawValue> namespacedRecord, String[] categoricals) {
        // categorical features
        for (int featureIdx = 0; featureIdx < CatBoostBulkScorer.this.categoricalFeatures.size(); featureIdx++) {
            FeatureNamespace feature = CatBoostBulkScorer.this.categoricalFeatures.get(featureIdx);
            RawValue rawValue = namespacedRecord.get(feature);
            String featureValue;
            if (rawValue == null) {
                featureValue = CatBoostEncoder.MISSING_CATEGORICAL;
            } else {
                if(rawValue.getValueType()  == RawValueType.SINGLE_STRING){
                    featureValue = rawValue.getSingleString();
                } else {
                    featureValue = String.valueOf(rawValue.getSingleCategorical());
                }
            }
            categoricals[featureIdx] = featureValue;
        }
    }

    private void processNumericals(NamespacedRecord<FeatureNamespace, RawValue> namespacedRecord, float[] numericals) {
        // numerical features
        for (int featureIdx = 0; featureIdx < CatBoostBulkScorer.this.numericalFeatures.size(); featureIdx++) {
            FeatureNamespace feature = CatBoostBulkScorer.this.numericalFeatures.get(featureIdx);
            RawValue rawValue = namespacedRecord.get(feature);
            float featureValue;
            if (rawValue == null) {
                featureValue = Float.NaN;
            } else {
                featureValue = (float) rawValue.getSingleNumerical();
            }
            numericals[featureIdx] = featureValue;
        }
    }

    private static double sigmoid(double x) {
        return 1 / (1 + Math.exp(-x));
    }

    @Override
    public void close() throws Exception {
        this.hotvectCatBoostModel.close();
    }
}
