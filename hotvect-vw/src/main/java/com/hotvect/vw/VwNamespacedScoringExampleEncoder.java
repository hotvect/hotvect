//package com.hotvect.vw;
//
//import com.hotvect.api.algodefinition.common.RewardFunction;
//import com.hotvect.api.codec.scoring.ScoringExampleEncoder;
//import com.hotvect.api.data.DataRecord;
//import com.hotvect.api.data.FeatureNamespace;
//import com.hotvect.api.data.HashedValue;
//import com.hotvect.api.data.HashedValueType;
//import com.hotvect.api.data.scoring.ScoringExample;
//import com.hotvect.core.hash.AuditableHasher;
//import com.hotvect.core.transform.regression.ScoringTransformer;
//import com.hotvect.core.util.DoubleFormatUtils;
//
//import java.util.EnumMap;
//import java.util.Map;
//import java.util.function.ToDoubleFunction;
//
//import static com.google.common.base.Preconditions.checkState;
//
//public class VwNamespacedScoringExampleEncoder<RECORD, FEATURE extends Enum<FEATURE> & FeatureNamespace, OUTCOME> implements ScoringExampleEncoder<RECORD, OUTCOME> {
//    private final static char[] VALID_VW_NAMESPACE_CHARS = "abcdefghijklmnopqrstuvwxyz".toCharArray();
//    private final boolean binary;
//    private final ToDoubleFunction<OUTCOME> outcomeToImportanceWeight;
//    private final RewardFunction<OUTCOME> rewardFunction;
//
//    private final Class<FEATURE> featureKey;
//    private final ScoringTransformer<RECORD, FEATURE> scoringTransformer;
//    private final AuditableHasher<FEATURE> hasher;
//
//    public VwNamespacedScoringExampleEncoder(ScoringTransformer<RECORD, FEATURE> scoringTransformer, RewardFunction<OUTCOME> rewardFunction, Class<FEATURE> featureKey) {
//        this(scoringTransformer, rewardFunction, featureKey, false, null);
//    }
//
//    public VwNamespacedScoringExampleEncoder(ScoringTransformer<RECORD, FEATURE> scoringTransformer, RewardFunction<OUTCOME> rewardFunction, Class<FEATURE> featureKey, boolean binary) {
//        this(scoringTransformer, rewardFunction, featureKey, binary, null);
//    }
//
//    public VwNamespacedScoringExampleEncoder(ScoringTransformer<RECORD, FEATURE> scoringTransformer, RewardFunction<OUTCOME> rewardFunction, Class<FEATURE> featureKey, boolean binary, ToDoubleFunction<OUTCOME> outcomeToImportanceWeight) {
//        this.featureKey = featureKey;
//        this.scoringTransformer = scoringTransformer;
//        this.rewardFunction = rewardFunction;
//        this.hasher = new AuditableHasher<>(featureKey);
//        this.binary = binary;
//        this.outcomeToImportanceWeight = outcomeToImportanceWeight;
//    }
//
//    public EnumMap<FEATURE, String> getNamespaceMapping(){
//        EnumMap<FEATURE, String> ret = new EnumMap<>(featureKey);
//        for (FEATURE FEATURE : this.featureKey.getEnumConstants()) {
//            int ns = FEATURE.ordinal();
//            checkState(ns < VALID_VW_NAMESPACE_CHARS.length,
//                    "Sorry you cannot have more than " + VALID_VW_NAMESPACE_CHARS.length +
//                            "namespaces for VW");
//
//            char namespace = VALID_VW_NAMESPACE_CHARS[ns];
//            ret.put(FEATURE, String.valueOf(namespace));
//        }
//        return ret;
//    }
//
//    @Override
//    public String apply(ScoringExample<RECORD, OUTCOME> toEncode) {
//        DataRecord<FEATURE, HashedValue> transformedAndHashed = hasher.apply(scoringTransformer.apply(toEncode.getRecord()));
//        return vwEncode(toEncode, transformedAndHashed);
//    }
//
//
//    private String vwEncode(
//            ScoringExample<RECORD, OUTCOME> example,
//            DataRecord<FEATURE, HashedValue> transformedAndHashed) {
//        StringBuilder sb = new StringBuilder();
//
//        double targetVariable = this.rewardFunction.applyAsDouble(example.getOutcome());
//        if (binary) {
//            sb.append(targetVariable > 0 ? "1" : "-1");
//        } else {
//            DoubleFormatUtils.formatDoubleFast(targetVariable, 6, 6, sb);
//        }
//
//        if (outcomeToImportanceWeight != null) {
//            sb.append(" ");
//            double weight = outcomeToImportanceWeight.applyAsDouble(example.getOutcome());
//            DoubleFormatUtils.formatDoubleFast(weight, 6, 6, sb);
//        }
//
//        EnumMap<FEATURE, HashedValue> features = transformedAndHashed.asEnumMap();
//
//        for (Map.Entry<FEATURE, HashedValue> entry : features.entrySet()) {
//            int ns = entry.getKey().ordinal();
//            checkState(ns < VALID_VW_NAMESPACE_CHARS.length,
//                    "Sorry you cannot have more than " + VALID_VW_NAMESPACE_CHARS.length +
//                            "namespaces for VW");
//
//            char namespace = VALID_VW_NAMESPACE_CHARS[ns];
//            sb.append(" |");
//            sb.append(namespace);
//            sb.append(" ");
//
//            var hashedValue = entry.getValue();
//            if(hashedValue.getValueType() == HashedValueType.NUMERICAL){
//                // Numericals
//                int[] numericalIndices = hashedValue.getNumericalIndices();
//                double[] numericalValues = hashedValue.getNumericalValues();
//
//                for (int j = 0; j < numericalIndices.length; j++) {
//                    int feature = numericalIndices[j];
//                    double value = numericalValues[j];
//                    sb.append(feature);
//                    sb.append(':');
//                    DoubleFormatUtils.formatDoubleFast(value, 6, 6, sb);
//                    sb.append(" ");
//                }
//            } else {
//                // Categoricals
//                int[] categoricalIndices = hashedValue.getCategoricalIndices();
//
//                for (int feature : categoricalIndices) {
//                    sb.append(feature);
//                    sb.append(':');
//                    sb.append('1');
//                    sb.append(" ");
//                }
//            }
//        }
//        return sb.toString();
//    }
//}
