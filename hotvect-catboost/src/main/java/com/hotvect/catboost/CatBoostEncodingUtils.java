package com.hotvect.catboost;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingOutcome;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.core.util.DoubleFormatUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

final class CatBoostEncodingUtils {
    private static final int FLOAT_FORMAT_PRECISION = 9;

    static final String MISSING_CATEGORICAL = "Categ_Null";
    static final String MISSING_NUMERICAL = "NaN";
    static final String MISSING_TEXT = "NA";

    private CatBoostEncodingUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    static void validateUsedFeatures(Iterable<? extends Namespace> usedFeatures) {
        Set<Namespace> notOfCatBoostType = new LinkedHashSet<>();
        for (Namespace feature : usedFeatures) {
            if (!(feature.getFeatureValueType() instanceof CatBoostFeatureType)) {
                notOfCatBoostType.add(feature);
            }
        }
        checkArgument(
                notOfCatBoostType.isEmpty(),
                "All features must have a CatboostFeatureType defined. Offending features: %s",
                notOfCatBoostType
        );
    }

    static <ACTION, OUTCOME> ByteBuffer encodeRows(
            List<TransformedAction<ACTION>> transformedActions,
            List<RankingOutcome<OUTCOME, ACTION>> outcomes,
            Iterable<? extends Namespace> usedFeatures,
            RewardFunction<OUTCOME> rewardFunction
    ) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < transformedActions.size(); i++) {
            RankingOutcome<OUTCOME, ACTION> outcome = outcomes.get(i);
            double reward = rewardFunction.applyAsDouble(outcome.outcome());
            appendDouble(sb, reward);
            sb.append('\t');

            NamespacedRecord<Namespace, Object> record = transformedActions.get(i).transformed();
            for (Namespace featureKey : usedFeatures) {
                CatBoostFeatureType catBoostFeatureType = (CatBoostFeatureType) featureKey.getFeatureValueType();
                appendFeature(featureKey, catBoostFeatureType, record.get(featureKey), sb);
                sb.append('\t');
            }
            sb.deleteCharAt(sb.length() - 1);
            sb.append('\n');
        }

        return ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static void appendFeature(Namespace featureKey, CatBoostFeatureType valueType, Object value, StringBuilder sb) {
        try {
            doAppendFeature(valueType, value, sb);
        } catch (RuntimeException e) {
            Throwable cause = Throwables.getRootCause(e);
            throw new RuntimeException(
                    Strings.lenientFormat(
                            "Error while processing feature key=%s, value=%s, valueType=%s",
                            featureKey,
                            value,
                            valueType
                    ),
                    cause
            );
        }
    }

    static void doAppendFeature(CatBoostFeatureType valueType, Object v, StringBuilder sb) {
        switch (valueType) {
            case CATEGORICAL -> {
                switch (v) {
                    case null -> sb.append(MISSING_CATEGORICAL);
                    case String s -> sb.append(s);
                    case Integer i -> sb.append(i);
                    case Long l -> sb.append(l);
                    case Boolean b -> sb.append(b);
                    default -> throw new RuntimeException(
                            "Unexpected value type for CATEGORICAL feature: "
                                    + v
                                    + ". Allowed types: null, String, Integer, Long, Boolean."
                    );
                }
            }
            case NUMERICAL -> {
                switch (v) {
                    case null -> sb.append(MISSING_NUMERICAL);
                    case Double d -> appendDouble(sb, d);
                    case Float f -> appendDouble(sb, f.doubleValue());
                    default -> throw new RuntimeException(
                            "Unexpected value type for NUMERICAL feature: "
                                    + v
                                    + " of type: "
                                    + v.getClass()
                                    + ". Allowed types: null, Double, Float."
                    );
                }
            }
            case TEXT -> {
                switch (v) {
                    case null -> sb.append(MISSING_TEXT);
                    case String[] arr when arr.length == 0 -> sb.append(MISSING_TEXT);
                    case String[] arr -> {
                        for (String string : arr) {
                            checkState(
                                    !Strings.isNullOrEmpty(string),
                                    "TEXT feature value in an array may not be empty or null, %s. If the entire feature is missing, return null instead.",
                                    v
                            );
                            checkState(
                                    !string.contains(" "),
                                    "Feature value may not contain spaces, %s. This is due to CatBoost's restriction.",
                                    v
                            );
                            sb.append(string).append(" ");
                        }
                        sb.deleteCharAt(sb.length() - 1);
                    }
                    default -> throw new RuntimeException(
                            "Unexpected value type for TEXT feature: " + v + ". Allowed types: null, String[]."
                    );
                }
            }
            case GROUP_ID -> {
                switch (v) {
                    case null -> sb.append(MISSING_CATEGORICAL);
                    case String s -> sb.append(s);
                    default -> throw new RuntimeException(
                            "Unexpected value type for GROUP_ID feature: " + v + ". Allowed types: null, String."
                    );
                }
            }
            case EMBEDDING -> {
                switch (v) {
                    case null -> sb.append(MISSING_NUMERICAL);
                    case double[] darr when darr.length == 0 -> sb.append(MISSING_NUMERICAL);
                    case double[] darr -> {
                        for (double num : darr) {
                            appendDouble(sb, num);
                            sb.append(";");
                        }
                        sb.deleteCharAt(sb.length() - 1);
                    }
                    case float[] farr when farr.length == 0 -> sb.append(MISSING_NUMERICAL);
                    case float[] farr -> {
                        for (float num : farr) {
                            appendDouble(sb, num);
                            sb.append(";");
                        }
                        sb.deleteCharAt(sb.length() - 1);
                    }
                    default -> throw new RuntimeException(
                            "Unexpected value type for EMBEDDING feature: " + v + ". Allowed types: null, double[], float[]."
                    );
                }
            }
            default -> throw new AssertionError("valueType " + valueType + " should be processed in appendFeatures.");
        }
    }

    private static void appendDouble(StringBuilder sb, double d) {
        DoubleFormatUtils.format(d, FLOAT_FORMAT_PRECISION, sb);
    }
}
