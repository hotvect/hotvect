package com.hotvect.catboost;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.api.data.scoring.ScoringDecision;
import com.hotvect.core.transform.ranking.ComputingBulkScorer;
import com.hotvect.core.transform.ranking.ComputingCandidate;
import com.hotvect.core.transform.ranking.ComputingRankingRequest;
import com.hotvect.core.transform.ranking.ComputingRankingTransformer;
import com.hotvect.onlineutils.concurrency.CommonPool;
import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RecursiveTask;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;
import static com.hotvect.core.util.Utils.checkCollectionIsEnumsOrNamespaceIdObjects;

public class CatBoostBulkScorer<SHARED, ACTION> implements ComputingBulkScorer<SHARED, ACTION> {
    private final ComputingRankingTransformer<SHARED, ACTION> transformer;
    private final HotvectCatBoostModel hotvectCatBoostModel;

    private final List<Namespace> numericalFeatures;
    private final List<Namespace> categoricalFeatures;
    private final List<Namespace> textFeatures;
    private final List<Namespace> embdeddedFeatures;

    private final int noForkThreshold;
    private final TaskType taskType;

    public CatBoostBulkScorer(
            ComputingRankingTransformer<SHARED, ACTION> transformer,
            HotvectCatBoostModel hotvectCatBoostModel,
            int noForkThreshold,
            String taskType
    ) {
        this.noForkThreshold = noForkThreshold;
        this.transformer = transformer;
        this.hotvectCatBoostModel = hotvectCatBoostModel;
        this.taskType = TaskType.fromString(taskType);

        checkCollectionIsEnumsOrNamespaceIdObjects(transformer.getUsedFeatures());

        Set<Namespace> Namespaces = transformer.getUsedFeatures();

        this.numericalFeatures = ImmutableList.copyOf(Namespaces.stream().filter(x -> x.getFeatureValueType() == CatBoostFeatureType.NUMERICAL).collect(Collectors.toList()));
        this.categoricalFeatures = ImmutableList.copyOf(Namespaces.stream().filter(x -> x.getFeatureValueType() == CatBoostFeatureType.CATEGORICAL).collect(Collectors.toList()));
        this.textFeatures = ImmutableList.copyOf(Namespaces.stream().filter(x -> x.getFeatureValueType() == CatBoostFeatureType.TEXT).collect(Collectors.toList()));
        this.embdeddedFeatures = ImmutableList.copyOf(Namespaces.stream().filter(x -> x.getFeatureValueType() == CatBoostFeatureType.EMBEDDING).collect(Collectors.toList()));
    }

    @Override
    public List<ScoringDecision<ACTION>> bulkScore(RankingRequest<SHARED, ACTION> rankingRequest) {
        ComputingRankingRequest<SHARED, ACTION> computingRankingRequest = transformer.prepare(rankingRequest);
        return this.doApply(computingRankingRequest);
    }

    @Override
    public List<ScoringDecision<ACTION>> bulkScore(ComputingRankingRequest<SHARED, ACTION> rankingRequest) {
        rankingRequest = this.transformer.prepare(rankingRequest);
        return this.doApply(rankingRequest);
    }


    public List<ScoringDecision<ACTION>> doApply(ComputingRankingRequest<SHARED, ACTION> rankingRequest) {
        if (rankingRequest.candidates().size()<=noForkThreshold){
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
        int actionSize = rankingRequest.candidates().size();
        List<TransformedAction<ACTION>> transformed = transformer.transform(rankingRequest);
        float[][] numericals = new float[actionSize][CatBoostBulkScorer.this.numericalFeatures.size()];
        float[][][] embeddings = new float[actionSize][CatBoostBulkScorer.this.embdeddedFeatures.size()][];
        String[][] categoricals = new String[actionSize][CatBoostBulkScorer.this.categoricalFeatures.size()];
        String[][] texts = new String[actionSize][CatBoostBulkScorer.this.textFeatures.size()];
        for (int actionIdx = 0; actionIdx < actionSize; actionIdx++) {
            var dataRecord = transformed.get(actionIdx).transformed();

            processNumericals(dataRecord, numericals[actionIdx]);

            processCategorical(dataRecord, categoricals[actionIdx]);

            processText(dataRecord, texts[actionIdx]);

            processEmbedding(dataRecord, embeddings[actionIdx]);

        }
        DoubleList predictedScores = CatBoostBulkScorer.this.hotvectCatBoostModel.predict(numericals, categoricals, texts, embeddings);
        if (this.taskType == TaskType.CLASSIFICATION) {
            for (int i = 0; i < predictedScores.size(); i++) {
                predictedScores.set(i, sigmoid(predictedScores.getDouble(i)));
            }
        }

        List<ScoringDecision<ACTION>> ret = new ArrayList<>(predictedScores.size());
        for (int i = 0; i < predictedScores.size(); i++) {
            ret.add(ScoringDecision.of(rankingRequest.rankingRequest().availableActions().get(i), predictedScores.getDouble(i)));
        }

        return ret;
    }

    private static final float[] MISSING_EMBEDDING = new float[]{Float.NaN};

    private void processEmbedding(NamespacedRecord<Namespace, Object> namespacedRecord, float[][] embeddings) {
        for (int featureIdx = 0; featureIdx < CatBoostBulkScorer.this.embdeddedFeatures.size(); featureIdx++) {
            Namespace feature = CatBoostBulkScorer.this.embdeddedFeatures.get(featureIdx);
            Object value = namespacedRecord.get(feature);

            final float[] featureValue;
            if (value == null) {
                featureValue = MISSING_EMBEDDING;
            } else if (value instanceof float[] fs) {
                if (fs.length == 0) {
                    featureValue = MISSING_EMBEDDING;
                } else {
                    featureValue = fs;
                }
            } else if(value instanceof double[] ds){
                if (ds.length == 0) {
                    featureValue = MISSING_EMBEDDING;
                } else {
                    float[] farr = new float[ds.length];
                    for (int i = 0; i < ds.length; i++) {
                        farr[i] = (float) ds[i];
                    }
                    featureValue = farr;
                }
            } else {
                throw new RuntimeException("Invalid type for embedding:%s" + value);
            }

            embeddings[featureIdx] = featureValue;
        }
    }

    private void processText(NamespacedRecord<Namespace, Object> namespacedRecord, String[] texts) {
        // text features
        for (int featureIdx = 0; featureIdx < CatBoostBulkScorer.this.textFeatures.size(); featureIdx++) {
            Namespace feature = CatBoostBulkScorer.this.textFeatures.get(featureIdx);
            Object value = namespacedRecord.get(feature);
            final String featureValue;
            if (value == null) {
                featureValue = CatBoostEncoder.MISSING_TEXT;
            } else if (value instanceof String[] arr) {
                if (arr.length == 0) {
                    featureValue = CatBoostEncoder.MISSING_TEXT;
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (String string : arr) {
                        checkState(!Strings.isNullOrEmpty(string), "Suspicious empty string:%s", (Object)arr);
                        checkState(!string.contains(" "), "Feature value may not contain spaces:%s", (Object)arr);
                        sb.append(string).append(" ");
                    }
                    // Remove excess space character
                    sb.deleteCharAt(sb.length() - 1);
                    featureValue = sb.toString();
                }
            } else {
                throw new RuntimeException("Invalid text type:" + value);
            }
            texts[featureIdx] = featureValue;
        }
    }

    private void processCategorical(NamespacedRecord<Namespace, Object> namespacedRecord, String[] categoricals) {
        // categorical features
        for (int featureIdx = 0; featureIdx < CatBoostBulkScorer.this.categoricalFeatures.size(); featureIdx++) {
            Namespace feature = CatBoostBulkScorer.this.categoricalFeatures.get(featureIdx);
            Object value = namespacedRecord.get(feature);

            final String featureValue;
            if (value == null) {
                featureValue = CatBoostEncoder.MISSING_CATEGORICAL;
            } else if (value instanceof String s) {
                featureValue = s;
            } else if (value instanceof Integer || value instanceof Long) {
                featureValue = value.toString();
            } else {
                throw new RuntimeException("Invalid categorical value:" + value);
            }
            categoricals[featureIdx] = featureValue;
        }
    }

    private void processNumericals(NamespacedRecord<Namespace, Object> namespacedRecord, float[] numericals) {
        // numerical features
        for (int featureIdx = 0; featureIdx < CatBoostBulkScorer.this.numericalFeatures.size(); featureIdx++) {
            Namespace feature = CatBoostBulkScorer.this.numericalFeatures.get(featureIdx);
            Object value = namespacedRecord.get(feature);

            final float featureValue;
            if (value == null) {
                featureValue = Float.NaN;
            } else if (value instanceof Double d) {
                featureValue = d.floatValue();
            } else if (value instanceof Float f){
                featureValue = f;
            } else {
                throw new RuntimeException("Invalid numerical value:" + value);
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
