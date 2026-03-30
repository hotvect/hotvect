package com.hotvect.catboost;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.hotvect.core.transform.ranking.StreamingRankingTransformer;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.api.data.scoring.ScoringDecision;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.*;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;
import static com.hotvect.core.util.Utils.checkCollectionIsEnumsOrNamespaceIdObjects;

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
    private final HotvectCatBoostModel hotvectCatBoostModel;

    private final List<Namespace> numericalFeatures;
    private final List<Namespace> categoricalFeatures;
    private final List<Namespace> textFeatures;
    private final List<Namespace> embdeddedFeatures;

    private final TaskType taskType;
    public CatBoostStreamingBulkScorer(
            StreamingRankingTransformer<SHARED, ACTION> transformer,
            HotvectCatBoostModel hotvectCatBoostModel,
            String taskType
    ) {
        this.transformer = transformer;
        this.hotvectCatBoostModel = hotvectCatBoostModel;
        this.taskType = TaskType.fromString(taskType);

        checkCollectionIsEnumsOrNamespaceIdObjects(transformer.getUsedFeatures());

        Set<? extends Namespace> namespaces = transformer.getUsedFeatures();
        this.numericalFeatures = ImmutableList.copyOf(
                namespaces.stream()
                        .filter(x -> x.getFeatureValueType() == CatBoostFeatureType.NUMERICAL)
                        .map(x -> (Namespace) x)
                        .collect(Collectors.toList())
        );
        this.categoricalFeatures = ImmutableList.copyOf(
                namespaces.stream()
                        .filter(x -> x.getFeatureValueType() == CatBoostFeatureType.CATEGORICAL)
                        .map(x -> (Namespace) x)
                        .collect(Collectors.toList())
        );
        this.textFeatures = ImmutableList.copyOf(
                namespaces.stream()
                        .filter(x -> x.getFeatureValueType() == CatBoostFeatureType.TEXT)
                        .map(x -> (Namespace) x)
                        .collect(Collectors.toList())
        );
        this.embdeddedFeatures = ImmutableList.copyOf(
                namespaces.stream()
                        .filter(x -> x.getFeatureValueType() == CatBoostFeatureType.EMBEDDING)
                        .map(x -> (Namespace) x)
                        .collect(Collectors.toList())
        );
    }

    @Override
    public List<ScoringDecision<ACTION>> bulkScore(RankingRequest<SHARED, ACTION> rankingRequest) {
        if (rankingRequest.availableActions().isEmpty()) return Collections.emptyList();

        // Chained pipeline (default ForkJoinPool.commonPool()):
        // transform stream -> batch lazily -> score batches in parallel -> flatten
        return transformer.transformBatchStream(rankingRequest)
                .parallel()
                .map(this::scoreTransformed)
                .flatMap(List::stream)
                .toList();
    }

    private List<ScoringDecision<ACTION>> scoreTransformed(List<TransformedAction<ACTION>> transformed) {
        int actionSize = transformed.size();
        float[][] numericals = new float[actionSize][this.numericalFeatures.size()];
        float[][][] embeddings = new float[actionSize][this.embdeddedFeatures.size()][];
        String[][] categoricals = new String[actionSize][this.categoricalFeatures.size()];
        String[][] texts = new String[actionSize][this.textFeatures.size()];

        for (int actionIdx = 0; actionIdx < actionSize; actionIdx++) {
            var dataRecord = transformed.get(actionIdx).transformed();
            processNumericals(dataRecord, numericals[actionIdx]);
            processCategorical(dataRecord, categoricals[actionIdx]);
            processText(dataRecord, texts[actionIdx]);
            processEmbedding(dataRecord, embeddings[actionIdx]);
        }

        DoubleList predictedScores = this.hotvectCatBoostModel.predict(numericals, categoricals, texts, embeddings);
        if (this.taskType == TaskType.CLASSIFICATION) {
            for (int i = 0; i < predictedScores.size(); i++) {
                predictedScores.set(i, sigmoid(predictedScores.getDouble(i)));
            }
        }

        List<ScoringDecision<ACTION>> ret = new ArrayList<>(predictedScores.size());
        for (int i = 0; i < predictedScores.size(); i++) {
            ret.add(ScoringDecision.of(transformed.get(i).action(), predictedScores.getDouble(i)));
        }
        return ret;
    }

    private static final float[] MISSING_EMBEDDING = new float[]{Float.NaN};

    private void processEmbedding(NamespacedRecord<Namespace, Object> namespacedRecord, float[][] embeddings) {
        for (int featureIdx = 0; featureIdx < this.embdeddedFeatures.size(); featureIdx++) {
            Namespace feature = this.embdeddedFeatures.get(featureIdx);
            Object value = namespacedRecord.get(feature);

            final float[] featureValue;
            switch (value) {
                case null -> featureValue = MISSING_EMBEDDING;
                case float[] fs -> {
                    if (fs.length == 0) {
                        featureValue = MISSING_EMBEDDING;
                    } else {
                        featureValue = fs;
                    }
                }
                case double[] ds -> {
                    if (ds.length == 0) {
                        featureValue = MISSING_EMBEDDING;
                    } else {
                        float[] farr = new float[ds.length];
                        for (int i = 0; i < ds.length; i++) {
                            farr[i] = (float) ds[i];
                        }
                        featureValue = farr;
                    }
                }
                default -> throw new RuntimeException("Invalid type for embedding:%s" + value);
            }

            embeddings[featureIdx] = featureValue;
        }
    }

    private void processText(NamespacedRecord<Namespace, Object> namespacedRecord, String[] texts) {
        for (int featureIdx = 0; featureIdx < this.textFeatures.size(); featureIdx++) {
            Namespace feature = this.textFeatures.get(featureIdx);
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
                        checkState(!Strings.isNullOrEmpty(string), "Suspicious empty string:%s", (Object) arr);
                        checkState(!string.contains(" "), "Feature value may not contain spaces:%s", (Object) arr);
                        sb.append(string).append(" ");
                    }
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
        for (int featureIdx = 0; featureIdx < this.categoricalFeatures.size(); featureIdx++) {
            Namespace feature = this.categoricalFeatures.get(featureIdx);
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
        for (int featureIdx = 0; featureIdx < this.numericalFeatures.size(); featureIdx++) {
            Namespace feature = this.numericalFeatures.get(featureIdx);
            Object value = namespacedRecord.get(feature);

            final float featureValue = switch (value) {
                case null -> Float.NaN;
                case Double d -> d.floatValue();
                case Float f -> f;
                default -> throw new RuntimeException("Invalid numerical value:" + value);
            };
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

