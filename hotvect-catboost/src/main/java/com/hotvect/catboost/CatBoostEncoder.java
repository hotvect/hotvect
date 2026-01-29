package com.hotvect.catboost;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.core.transform.ranking.ComputingRankingRequest;
import com.hotvect.core.transform.ranking.ComputingRankingTransformer;
import com.hotvect.core.util.DoubleFormatUtils;
import com.hotvect.utils.ListTransform;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.stream.Collectors.toSet;


public class CatBoostEncoder<SHARED, ACTION, OUTCOME> implements RankingExampleEncoder<SHARED, ACTION, OUTCOME> {
    private static final int FLOAT_FORMAT_PRECISION = 9;
    private final ComputingRankingTransformer<SHARED, ACTION> transformer;
    private final RewardFunction<OUTCOME> rewardFunction;

    public CatBoostEncoder(ComputingRankingTransformer<SHARED, ACTION> transformer, RewardFunction<OUTCOME> rewardFunction) {
        this.transformer = transformer;
        Set<Namespace> notOfCatBoostType = transformer.getUsedFeatures().stream().filter(x -> !(x.getFeatureValueType() instanceof CatBoostFeatureType)).collect(toSet());
        checkArgument(notOfCatBoostType.isEmpty(), "All features must have a CatboostFeatureType defined. Offending features: %s", notOfCatBoostType);
        this.rewardFunction = rewardFunction;
    }

    @Override
    public Optional<String> schemaDescription() {
        return Optional.of(new CatBoostColumnDescriptionGenerator().apply(this.transformer));
    }

    @Override
    public String apply(RankingExample<SHARED, ACTION, OUTCOME> toEncode) {
        ComputingRankingRequest<SHARED, ACTION> memoized = transformer.prepare(toEncode.rankingRequest());
        List<TransformedAction<ACTION>> transformedActions = transformer.transform(memoized);
        List<NamespacedRecord<Namespace, Object>> transformed = ListTransform.map(transformedActions, TransformedAction::transformed);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < transformed.size(); i++) {
            RankingOutcome<OUTCOME, ACTION> outcome = toEncode.outcomes().get(i);
            double reward = rewardFunction.applyAsDouble(outcome.outcome());
            appendDouble(sb, reward);
            sb.append('\t');

            NamespacedRecord<Namespace, Object> record = transformed.get(i);

            for (Namespace featureKey : transformer.getUsedFeatures()) {
                CatBoostFeatureType catBoostFeatureType = (CatBoostFeatureType) featureKey.getFeatureValueType();
                appendFeature(featureKey, catBoostFeatureType, record.get(featureKey), sb);
                sb.append('\t');
            }
            // Remove excessive tab
            sb.deleteCharAt(sb.length() - 1);
            sb.append('\n');
        }

        if(!sb.isEmpty()){
            // If we have added records, remove the last excessive new line
            sb.deleteCharAt(sb.length() - 1);
        }

        return sb.toString();
    }

    private void appendDouble(StringBuilder sb, double d) {
        DoubleFormatUtils.format(d, FLOAT_FORMAT_PRECISION, sb);
    }

    public static final String MISSING_CATEGORICAL = "Categ_Null";
    public static final String MISSING_NUMERICAL = "NaN";

    public static final String MISSING_TEXT = "NA";

    private void appendFeature(Namespace featureKey, CatBoostFeatureType valueType, Object Object, StringBuilder sb) {
        try {
            doAppendFeature(valueType, Object, sb);
        }catch (RuntimeException e){
            Throwable cause = Throwables.getRootCause(e);
            throw new RuntimeException(Strings.lenientFormat("Error while processing feature key=%s, value=%s, valueType=%s", featureKey, Object, valueType), cause);
        }
    }

    private void doAppendFeature(CatBoostFeatureType valueType, Object v, StringBuilder sb) {
        switch (valueType) {
            case CATEGORICAL -> {
                // CATEGORICAL: allowed null, String, Integer, Long
                if (v == null) {
                    sb.append(MISSING_CATEGORICAL);
                } else if (v instanceof String || v instanceof Integer || v instanceof Long || v instanceof Boolean) {
                    sb.append(v);
                } else {
                    throw new RuntimeException("Unexpected value type for CATEGORICAL feature: " + v
                            + ". Allowed types: null, String, Integer, Long.");
                }
            }
            case NUMERICAL -> {
                // NUMERICAL: allowed null, Double, Float
                if (v == null) {
                    sb.append(MISSING_NUMERICAL);
                } else if (v instanceof Double d) {
                    appendDouble(sb, d);
                } else if (v instanceof Float f) {
                    appendDouble(sb, f.doubleValue());
                } else {
                    throw new RuntimeException("Unexpected value type for NUMERICAL feature: " + v
                            + " of type: " + v.getClass() + ". Allowed types: null, Double, Float.");
                }
            }
            case TEXT -> {
                // TEXT: allowed null or String[]. If null => " ", no empty arrays, no spaces in strings.
                if (v == null) {
                    sb.append(" ");
                } else if (v instanceof String[] arr) {
                    if(arr.length == 0){
                        sb.append(MISSING_TEXT);
                        return;
                    }
                    for (String string : arr) {
                        checkState(!Strings.isNullOrEmpty(string), "TEXT feature value in an array may not be empty or null, %s. If the entire feature is missing, return null instead.", v);
                        checkState(!string.contains(" "), "Feature value may not contain spaces, %s. This is due to CatBoost's restriction.", v);
                        sb.append(string).append(" ");
                    }
                    sb.deleteCharAt(sb.length() - 1);

                } else {
                    throw new RuntimeException("Unexpected value type for TEXT feature: " + v
                            + ". Allowed types: null, String[].");
                }
            }
            case GROUP_ID -> {
                // GROUP_ID: allowed null or String
                if (v == null) {
                    sb.append(MISSING_CATEGORICAL);
                } else if (v instanceof String) {
                    sb.append(v);
                } else {
                    throw new RuntimeException("Unexpected value type for GROUP_ID feature: " + v
                            + ". Allowed types: null, String.");
                }
            }
            case EMBEDDING -> {
                // EMBEDDING: allowed null, double[], float[]
                if (v == null) {
                    sb.append(MISSING_NUMERICAL);
                } else if (v instanceof double[] darr) {
                    if (darr.length == 0) {
                        sb.append(MISSING_NUMERICAL);
                    } else {
                        for (double num : darr) {
                            appendDouble(sb, num);
                            sb.append(";");
                        }
                        sb.deleteCharAt(sb.length() - 1);
                    }
                } else if (v instanceof float[] farr) {
                    if (farr.length == 0) {
                        sb.append(MISSING_NUMERICAL);
                    } else {
                        for (float num : farr) {
                            appendDouble(sb, (double) num);
                            sb.append(";");
                        }
                        sb.deleteCharAt(sb.length() - 1);
                    }
                } else {
                    throw new RuntimeException("Unexpected value type for EMBEDDING feature: " + v
                            + ". Allowed types: null, double[], float[].");
                }
            }
            default -> throw new AssertionError("valueType " + valueType + " should be processed in appendFeatures.");
        }
    }
}
