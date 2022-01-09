package com.eshioji.hotvect.vw;

import com.eshioji.hotvect.api.codec.regression.ExampleEncoder;
import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.HashedValue;
import com.eshioji.hotvect.api.data.HashedValueType;
import com.eshioji.hotvect.api.data.regression.Example;
import com.eshioji.hotvect.core.hash.AuditableHasher;
import com.eshioji.hotvect.core.transform.regression.Transformer;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import static com.google.common.base.Preconditions.checkState;

public class VwNamespacedExampleEncoder<RECORD, FEATURE extends Enum<FEATURE> & FeatureNamespace> implements ExampleEncoder<RECORD> {
    private final static char[] VALID_VW_NAMESPACE_CHARS = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    private final boolean binary;
    private final DoubleUnaryOperator targetToImportanceWeight;

    private final Class<FEATURE> hashedKey;
    private final Transformer<RECORD, FEATURE> transformer;
    private final AuditableHasher<FEATURE> hasher;

    public VwNamespacedExampleEncoder(Transformer<RECORD, FEATURE> transformer, Class<FEATURE> hashedKey) {
        this(transformer, hashedKey, false, null);
    }

    public VwNamespacedExampleEncoder(Transformer<RECORD, FEATURE> transformer, Class<FEATURE> hashedKey, boolean binary) {
        this(transformer, hashedKey, binary, null);
    }

    public VwNamespacedExampleEncoder(Transformer<RECORD, FEATURE> transformer, Class<FEATURE> hashedKey, boolean binary, DoubleUnaryOperator targetToImportanceWeight) {
        this.hashedKey = hashedKey;
        this.transformer = transformer;
        this.hasher = new AuditableHasher<>(hashedKey);
        this.binary = binary;
        this.targetToImportanceWeight = targetToImportanceWeight;
    }

    public EnumMap<FEATURE, String> getNamespaceMapping(){
        EnumMap<FEATURE, String> ret = new EnumMap<>(hashedKey);
        for (FEATURE FEATURE : this.hashedKey.getEnumConstants()) {
            int ns = FEATURE.ordinal();
            checkState(ns < VALID_VW_NAMESPACE_CHARS.length,
                    "Sorry you cannot have more than " + VALID_VW_NAMESPACE_CHARS.length +
                            "namespaces for VW");

            char namespace = VALID_VW_NAMESPACE_CHARS[ns];
            ret.put(FEATURE, String.valueOf(namespace));
        }
        return ret;
    }

    @Override
    public String apply(Example<RECORD> toEncode) {
        DataRecord<FEATURE, HashedValue> transformedAndHashed = hasher.apply(transformer.apply(toEncode.getRecord()));
        return vwEncode(toEncode, transformedAndHashed, binary, targetToImportanceWeight);
    }


    private String vwEncode(
            Example<RECORD> request,
            DataRecord<FEATURE, HashedValue> transformedAndHashed,
            boolean binary,
            DoubleUnaryOperator targetToImportanceWeight) {
        StringBuilder sb = new StringBuilder();

        double targetVariable = request.getTarget();
        if (binary) {
            sb.append(targetVariable > 0 ? "1" : "-1");
        } else {
            DoubleFormatUtils.formatDoubleFast(targetVariable, 6, 6, sb);
        }

        if (targetToImportanceWeight != null) {
            sb.append(" ");
            double weight = targetToImportanceWeight.applyAsDouble(targetVariable);
            DoubleFormatUtils.formatDoubleFast(weight, 6, 6, sb);
        }

        EnumMap<FEATURE, HashedValue> features = transformedAndHashed.asEnumMap();

        for (Map.Entry<FEATURE, HashedValue> entry : features.entrySet()) {
            int ns = entry.getKey().ordinal();
            checkState(ns < VALID_VW_NAMESPACE_CHARS.length,
                    "Sorry you cannot have more than " + VALID_VW_NAMESPACE_CHARS.length +
                            "namespaces for VW");

            char namespace = VALID_VW_NAMESPACE_CHARS[ns];
            sb.append(" |");
            sb.append(namespace);
            sb.append(" ");

            var hashedValue = entry.getValue();
            if(hashedValue.getValueType() == HashedValueType.NUMERICAL){
                // Numericals
                int[] numericalIndices = hashedValue.getNumericalIndices();
                double[] numericalValues = hashedValue.getNumericalValues();

                for (int j = 0; j < numericalIndices.length; j++) {
                    int feature = numericalIndices[j];
                    double value = numericalValues[j];
                    sb.append(feature);
                    sb.append(':');
                    DoubleFormatUtils.formatDoubleFast(value, 6, 6, sb);
                    sb.append(" ");
                }
            } else {
                // Categoricals
                int[] categoricalIndices = hashedValue.getCategoricalIndices();

                for (int j = 0; j < categoricalIndices.length; j++) {
                    int feature = categoricalIndices[j];
                    sb.append(feature);
                    sb.append(':');
                    sb.append('1');
                    sb.append(" ");
                }
            }



        }

        return sb.toString();
    }

}
