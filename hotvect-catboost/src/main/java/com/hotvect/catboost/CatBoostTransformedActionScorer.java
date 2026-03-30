package com.hotvect.catboost;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.api.data.scoring.ScoringDecision;
import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;
import static com.hotvect.core.util.Utils.checkCollectionIsEnumsOrNamespaceIdObjects;

final class CatBoostTransformedActionScorer<ACTION> {
    private static final float[] MISSING_EMBEDDING = new float[]{Float.NaN};

    private final HotvectCatBoostModel hotvectCatBoostModel;
    private final List<Namespace> numericalFeatures;
    private final List<Namespace> categoricalFeatures;
    private final List<Namespace> textFeatures;
    private final List<Namespace> embdeddedFeatures;
    private final TaskType taskType;

    CatBoostTransformedActionScorer(
            Set<? extends Namespace> usedFeatures,
            HotvectCatBoostModel hotvectCatBoostModel,
            String taskType
    ) {
        this.hotvectCatBoostModel = Objects.requireNonNull(hotvectCatBoostModel, "hotvectCatBoostModel");
        this.taskType = TaskType.fromString(taskType);

        checkCollectionIsEnumsOrNamespaceIdObjects(usedFeatures);

        this.numericalFeatures = ImmutableList.copyOf(
                usedFeatures.stream()
                        .filter(x -> x.getFeatureValueType() == CatBoostFeatureType.NUMERICAL)
                        .map(x -> (Namespace) x)
                        .collect(Collectors.toList())
        );
        this.categoricalFeatures = ImmutableList.copyOf(
                usedFeatures.stream()
                        .filter(x -> x.getFeatureValueType() == CatBoostFeatureType.CATEGORICAL)
                        .map(x -> (Namespace) x)
                        .collect(Collectors.toList())
        );
        this.textFeatures = ImmutableList.copyOf(
                usedFeatures.stream()
                        .filter(x -> x.getFeatureValueType() == CatBoostFeatureType.TEXT)
                        .map(x -> (Namespace) x)
                        .collect(Collectors.toList())
        );
        this.embdeddedFeatures = ImmutableList.copyOf(
                usedFeatures.stream()
                        .filter(x -> x.getFeatureValueType() == CatBoostFeatureType.EMBEDDING)
                        .map(x -> (Namespace) x)
                        .collect(Collectors.toList())
        );
    }

    List<ScoringDecision<ACTION>> scoreTransformed(List<TransformedAction<ACTION>> transformed) {
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
            ret.add(
                    ScoringDecision.of(
                            transformed.get(i).action(),
                            predictedScores.getDouble(i),
                            transformed.get(i).additionalProperties()
                    )
            );
        }
        return ret;
    }

    void close() throws Exception {
        this.hotvectCatBoostModel.close();
    }

    private void processEmbedding(NamespacedRecord<Namespace, Object> namespacedRecord, float[][] embeddings) {
        for (int featureIdx = 0; featureIdx < this.embdeddedFeatures.size(); featureIdx++) {
            Namespace feature = this.embdeddedFeatures.get(featureIdx);
            Object value = namespacedRecord.get(feature);

            embeddings[featureIdx] = switch (value) {
                case null -> MISSING_EMBEDDING;
                case float[] fs when fs.length == 0 -> MISSING_EMBEDDING;
                case float[] fs -> fs;
                case double[] ds when ds.length == 0 -> MISSING_EMBEDDING;
                case double[] ds -> {
                    float[] farr = new float[ds.length];
                    for (int i = 0; i < ds.length; i++) {
                        farr[i] = (float) ds[i];
                    }
                    yield farr;
                }
                default -> throw new RuntimeException("Invalid type for embedding:%s" + value);
            };
        }
    }

    private void processText(NamespacedRecord<Namespace, Object> namespacedRecord, String[] texts) {
        for (int featureIdx = 0; featureIdx < this.textFeatures.size(); featureIdx++) {
            Namespace feature = this.textFeatures.get(featureIdx);
            Object value = namespacedRecord.get(feature);

            texts[featureIdx] = switch (value) {
                case null -> CatBoostEncoder.MISSING_TEXT;
                case String[] arr when arr.length == 0 -> CatBoostEncoder.MISSING_TEXT;
                case String[] arr -> {
                    StringBuilder sb = new StringBuilder();
                    for (String string : arr) {
                        checkState(!Strings.isNullOrEmpty(string), "Suspicious empty string:%s", (Object) arr);
                        checkState(!string.contains(" "), "Feature value may not contain spaces:%s", (Object) arr);
                        sb.append(string).append(" ");
                    }
                    sb.deleteCharAt(sb.length() - 1);
                    yield sb.toString();
                }
                default -> throw new RuntimeException("Invalid text type:" + value);
            };
        }
    }

    private void processCategorical(NamespacedRecord<Namespace, Object> namespacedRecord, String[] categoricals) {
        for (int featureIdx = 0; featureIdx < this.categoricalFeatures.size(); featureIdx++) {
            Namespace feature = this.categoricalFeatures.get(featureIdx);
            Object value = namespacedRecord.get(feature);

            categoricals[featureIdx] = switch (value) {
                case null -> CatBoostEncoder.MISSING_CATEGORICAL;
                case String s -> s;
                case Integer i -> i.toString();
                case Long l -> l.toString();
                default -> throw new RuntimeException("Invalid categorical value:" + value);
            };
        }
    }

    private void processNumericals(NamespacedRecord<Namespace, Object> namespacedRecord, float[] numericals) {
        for (int featureIdx = 0; featureIdx < this.numericalFeatures.size(); featureIdx++) {
            Namespace feature = this.numericalFeatures.get(featureIdx);
            Object value = namespacedRecord.get(feature);

            numericals[featureIdx] = switch (value) {
                case null -> Float.NaN;
                case Double d -> d.floatValue();
                case Float f -> f;
                default -> throw new RuntimeException("Invalid numerical value:" + value);
            };
        }
    }

    private static double sigmoid(double x) {
        return 1 / (1 + Math.exp(-x));
    }
}
