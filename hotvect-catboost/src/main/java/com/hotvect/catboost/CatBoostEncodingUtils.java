package com.hotvect.catboost;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.data.AvailableAction;
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
            List<AvailableAction<ACTION>> actions,
            List<TransformedAction<ACTION>> transformedActions,
            List<RankingOutcome<OUTCOME, ACTION>> outcomes,
            Iterable<? extends Namespace> usedFeatures,
            RewardFunction<OUTCOME> rewardFunction
    ) {
        checkArgument(
                transformedActions.size() == actions.size(),
                "RankingTransformer returned %s transformed actions for %s actions",
                transformedActions.size(),
                actions.size()
        );
        checkArgument(
                outcomes.size() == actions.size(),
                "RankingExample has %s outcomes for %s actions",
                outcomes.size(),
                actions.size()
        );

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < actions.size(); i++) {
            String actionId = actions.get(i).actionId();
            TransformedAction<ACTION> transformedAction = transformedActions.get(i);
            RankingOutcome<OUTCOME, ACTION> outcome = outcomes.get(i);
            checkArgument(
                    transformedAction.actionId().equals(actionId),
                    "RankingTransformer returned transformed action id %s at position %s, expected %s",
                    transformedAction.actionId(),
                    i,
                    actionId
            );
            checkArgument(
                    outcome.rankingDecision().actionId().equals(actionId),
                    "RankingExample outcome action id %s at position %s, expected %s",
                    outcome.rankingDecision().actionId(),
                    i,
                    actionId
            );
            double reward = rewardFunction.applyAsDouble(outcome.outcome());
            appendDouble(sb, reward);
            sb.append('\t');

            NamespacedRecord<Namespace, Object> record = transformedAction.transformed();
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
            case CATEGORICAL -> appendEscapedDelimitedField(normalizeCategoricalValue(v), sb);
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
            case TEXT -> appendEscapedDelimitedField(normalizeTextValue(v), sb);
            case GROUP_ID -> appendEscapedDelimitedField(normalizeGroupIdValue(v), sb);
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

    static String normalizeCategoricalValue(Object v) {
        return switch (v) {
            case null -> MISSING_CATEGORICAL;
            case String s -> s;
            case Integer i -> i.toString();
            case Long l -> l.toString();
            case Boolean b -> b.toString();
            default -> throw new RuntimeException(
                    "Unexpected value type for CATEGORICAL feature: "
                            + v
                            + ". Allowed types: null, String, Integer, Long, Boolean."
            );
        };
    }

    static String normalizeTextValue(Object v) {
        return switch (v) {
            case null -> MISSING_TEXT;
            case String[] arr when arr.length == 0 -> MISSING_TEXT;
            case String[] arr -> {
                StringBuilder textBuilder = new StringBuilder();
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
                    textBuilder.append(string).append(" ");
                }
                textBuilder.deleteCharAt(textBuilder.length() - 1);
                yield textBuilder.toString();
            }
            default -> throw new RuntimeException(
                    "Unexpected value type for TEXT feature: " + v + ". Allowed types: null, String[]."
            );
        };
    }

    static String normalizeGroupIdValue(Object v) {
        return switch (v) {
            case null -> MISSING_CATEGORICAL;
            case String s -> s;
            default -> throw new RuntimeException(
                    "Unexpected value type for GROUP_ID feature: " + v + ". Allowed types: null, String."
            );
        };
    }

    private static void appendDouble(StringBuilder sb, double d) {
        DoubleFormatUtils.format(d, FLOAT_FORMAT_PRECISION, sb);
    }

    /**
     * Emit one CatBoost DSV field using the RFC4180 quoting rules that CatBoost's parser accepts.
     * The common case stays allocation-free beyond the caller-provided builder: we scan once, append
     * raw values unchanged when no special characters are present, and only start quote-doubling from
     * the first delimiter-sensitive character.
     */
    private static void appendEscapedDelimitedField(String raw, StringBuilder sb) {
        int firstSpecialCharacterIndex = -1;
        for (int i = 0; i < raw.length(); i++) {
            if (isDelimitedFieldSpecialCharacter(raw.charAt(i))) {
                firstSpecialCharacterIndex = i;
                break;
            }
        }
        if (firstSpecialCharacterIndex < 0) {
            sb.append(raw);
            return;
        }
        sb.append('"');
        sb.append(raw, 0, firstSpecialCharacterIndex);
        for (int i = firstSpecialCharacterIndex; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '"') {
                sb.append("\"\"");
            } else {
                sb.append(ch);
            }
        }
        sb.append('"');
    }

    private static boolean isDelimitedFieldSpecialCharacter(char ch) {
        return ch == '\t' || ch == '\n' || ch == '\r' || ch == '"';
    }
}
