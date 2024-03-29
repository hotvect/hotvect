package com.hotvect.vw;

import com.hotvect.api.codec.ExampleEncoder;
import com.hotvect.api.data.DataRecord;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.hashed.HashedValue;
import com.hotvect.api.data.raw.Example;
import com.hotvect.core.hash.AuditableHasher;
import com.hotvect.core.transform.Transformer;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import static com.google.common.base.Preconditions.checkState;

public class VwNamespacedExampleEncoder<R, H extends Enum<H> & FeatureNamespace> implements ExampleEncoder<R> {
    private final static char[] VALID_VW_NAMESPACE_CHARS = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    private final boolean binary;
    private final DoubleUnaryOperator targetToImportanceWeight;

    private final Class<H> hashedKey;
    private final Transformer<R, H> transformer;
    private final AuditableHasher<H> hasher;

    public VwNamespacedExampleEncoder(Transformer<R, H> transformer, Class<H> hashedKey) {
        this(transformer, hashedKey, false, null);
    }

    public VwNamespacedExampleEncoder(Transformer<R, H> transformer, Class<H> hashedKey, boolean binary) {
        this(transformer, hashedKey, binary, null);
    }

    public VwNamespacedExampleEncoder(Transformer<R, H> transformer, Class<H> hashedKey, boolean binary, DoubleUnaryOperator targetToImportanceWeight) {
        this.hashedKey = hashedKey;
        this.transformer = transformer;
        this.hasher = new AuditableHasher<>(hashedKey);
        this.binary = binary;
        this.targetToImportanceWeight = targetToImportanceWeight;
    }

    public EnumMap<H, String> getNamespaceMapping(){
        EnumMap<H, String> ret = new EnumMap<>(hashedKey);
        for (H h : this.hashedKey.getEnumConstants()) {
            int ns = h.ordinal();
            checkState(ns < VALID_VW_NAMESPACE_CHARS.length,
                    "Sorry you cannot have more than " + VALID_VW_NAMESPACE_CHARS.length +
                            "namespaces for VW");

            char namespace = VALID_VW_NAMESPACE_CHARS[ns];
            ret.put(h, String.valueOf(namespace));
        }
        return ret;
    }

    @Override
    public String apply(Example<R> toEncode) {
        DataRecord<H, HashedValue> transformedAndHashed = hasher.apply(transformer.apply(toEncode.getRecord()));
        return vwEncode(toEncode, transformedAndHashed, binary, targetToImportanceWeight);
    }


    private String vwEncode(
            Example<R> request,
            DataRecord<H, HashedValue> transformedAndHashed,
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

        EnumMap<H, HashedValue> features = transformedAndHashed.asEnumMap();

        for (Map.Entry<H, HashedValue> entry : features.entrySet()) {
            int ns = entry.getKey().ordinal();
            checkState(ns < VALID_VW_NAMESPACE_CHARS.length,
                    "Sorry you cannot have more than " + VALID_VW_NAMESPACE_CHARS.length +
                            "namespaces for VW");

            char namespace = VALID_VW_NAMESPACE_CHARS[ns];
            sb.append(" |");
            sb.append(namespace);
            sb.append(" ");

            int[] indices = entry.getValue().getCategoricals();
            double[] values = entry.getValue().getNumericals();

            for (int j = 0; j < indices.length; j++) {
                int feature = indices[j];
                double value = values[j];
                sb.append(feature);
                sb.append(':');
                DoubleFormatUtils.formatDoubleFast(value, 6, 6, sb);
                sb.append(" ");
            }


        }

        return sb.toString();
    }

}
