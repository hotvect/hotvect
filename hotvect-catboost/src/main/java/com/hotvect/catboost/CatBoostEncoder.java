package com.hotvect.catboost;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.RawValue;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;
import com.hotvect.api.transformation.ranking.MemoizableRankingTransformer;
import com.hotvect.api.transformation.ranking.MemoizedRankingRequest;
import com.hotvect.core.util.DoubleFormatUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.stream.Collectors.toSet;


public class CatBoostEncoder<SHARED, ACTION, OUTCOME> implements RankingExampleEncoder<SHARED, ACTION, OUTCOME> {
    private static final int FLOAT_FORMAT_PRECISION = 9;
    private final MemoizableRankingTransformer<SHARED, ACTION> transformer;
    private final RewardFunction<OUTCOME> rewardFunction;

    public CatBoostEncoder(MemoizableRankingTransformer<SHARED, ACTION> transformer, RewardFunction<OUTCOME> rewardFunction) {
        this.transformer = transformer;
        Set<FeatureNamespace> notOfCatBoostType = transformer.getUsedFeatures().stream().filter(x -> !(x.getFeatureValueType() instanceof CatBoostFeatureType)).collect(toSet());
        checkArgument(notOfCatBoostType.isEmpty(), "All features must be CatBoostFeatureType. Offending features: %s", notOfCatBoostType);
        this.rewardFunction = rewardFunction;
    }

    @Override
    public Optional<String> schemaDescription() {
        return Optional.of(new CatBoostColumnDescriptionGenerator().apply(this.transformer));
    }

    @Override
    public String apply(RankingExample<SHARED, ACTION, OUTCOME> toEncode) {
        MemoizedRankingRequest<SHARED, ACTION> memoized = transformer.memoize(toEncode.getRankingRequest());
        List<NamespacedRecord<FeatureNamespace, RawValue>> transformed =
                transformer.apply(
                        memoized
                );
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < transformed.size(); i++) {
            RankingOutcome<OUTCOME, ACTION> outcome = toEncode.getOutcomes().get(i);
            double reward = rewardFunction.applyAsDouble(outcome.getOutcome());
            appendDouble(sb, reward);
            sb.append('\t');

            NamespacedRecord<FeatureNamespace, RawValue> record = transformed.get(i);

            for (FeatureNamespace featureKey : transformer.getUsedFeatures()) {
                CatBoostFeatureType catBoostFeatureType = (CatBoostFeatureType) featureKey.getFeatureValueType();
                appendFeature(featureKey, catBoostFeatureType, record.get(featureKey), sb);
                sb.append('\t');
            }
            // Remove excessive tab
            sb.deleteCharAt(sb.length() - 1);
            sb.append('\n');
        }

        if(sb.length() > 0){
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

    private void appendFeature(FeatureNamespace featureKey, CatBoostFeatureType valueType, RawValue rawValue, StringBuilder sb) {
        try {
            doAppendFeature(valueType, rawValue, sb);
        }catch (RuntimeException e){
            Throwable cause = Throwables.getRootCause(e);
            throw new RuntimeException(Strings.lenientFormat("Error while processing feature key=%s, value=%s, valueType=%s", featureKey, rawValue, valueType), cause);
        }
    }

    private void doAppendFeature(CatBoostFeatureType valueType, RawValue rawValue, StringBuilder sb) {
        if (valueType == CatBoostFeatureType.CATEGORICAL){
            String v = rawValue == null ? MISSING_CATEGORICAL : rawValue.getSingleString();
            sb.append(v);
        } else if (valueType == CatBoostFeatureType.NUMERICAL){
            if(rawValue == null){
                sb.append(MISSING_NUMERICAL);
            } else {
                appendDouble(sb, rawValue.getSingleNumerical());
            }
        } else if(valueType == CatBoostFeatureType.TEXT){
            if (rawValue == null || rawValue.getStrings().length == 0){
                // Catboost requires an empty column
                sb.append(" ");
            } else {
                for (String string : rawValue.getStrings()) {
                    checkState(!Strings.isNullOrEmpty(string), "Suspicious empty string:%s", rawValue);
                    checkState(!string.contains(" "), "Feature value may not contain spaces, %s", rawValue);
                    sb.append(string);
                    sb.append(" ");
                }
                // Remove excess space character
                sb.deleteCharAt(sb.length() - 1);
            }
        } else if (valueType == CatBoostFeatureType.GROUP_ID) {
            sb.append(rawValue.getSingleString());
        } else if (valueType == CatBoostFeatureType.EMBEDDING){
            if (rawValue == null || rawValue.getNumericals().length == 0){
                sb
                        .append(MISSING_NUMERICAL);
            } else {
                for (double numerical : rawValue.getNumericals()) {
                    appendDouble(sb, numerical);
                    sb.append(";");
                }
                // Remove excess space character
                sb.deleteCharAt(sb.length() - 1);
            }
        } else {
            throw new AssertionError("valueType " + valueType + " should be processed in appendFeatures.");
        }
    }
}
