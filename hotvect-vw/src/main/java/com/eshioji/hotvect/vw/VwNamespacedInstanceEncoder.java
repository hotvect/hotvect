package com.eshioji.hotvect.vw;

import com.eshioji.hotvect.api.codec.ExampleEncoder;
import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.hashed.HashedValue;
import com.eshioji.hotvect.api.data.raw.Example;
import com.eshioji.hotvect.api.data.raw.RawNamespace;
import com.eshioji.hotvect.api.data.raw.RawValue;
import com.eshioji.hotvect.core.hash.AuditableHasher;
import com.eshioji.hotvect.core.transform.Transformer;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import static com.google.common.base.Preconditions.checkState;

public class VwNamespacedInstanceEncoder<R, H extends Enum<H> & FeatureNamespace> implements ExampleEncoder<R> {
    private final static char[] VALID_VW_NAMESPACE_CHARS = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    private final boolean binary;
    private final DoubleUnaryOperator targetToImportanceWeight;

    private final Class<H> hashedKey;
    private final Transformer<R, H> transformer;
    private final AuditableHasher<H> hasher;

    public VwNamespacedInstanceEncoder(Transformer<R, H> transformer, Class<H> hashedKey) {
        this(transformer, hashedKey, false, null);
    }

    public VwNamespacedInstanceEncoder(Transformer<R, H> transformer, Class<H> hashedKey, boolean binary) {
        this(transformer, hashedKey, binary, null);
    }

    public VwNamespacedInstanceEncoder(Transformer<R, H> transformer, Class<H> hashedKey, boolean binary, DoubleUnaryOperator targetToImportanceWeight) {
        this.hashedKey = hashedKey;
        this.transformer = transformer;
        this.hasher = new AuditableHasher<>(hashedKey);
        this.binary = binary;
        this.targetToImportanceWeight = targetToImportanceWeight;
    }

    public EnumMap<H, String> getNamespaceMapping(){
        EnumMap<H, String> ret = new EnumMap<>(hashedKey);
        for (H h : this.hashedKey.getEnumConstants()) {
            var ns = h.ordinal();
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
        var transformedAndHashed = hasher.apply(transformer.apply(toEncode.getRecord()));
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
            var weight = targetToImportanceWeight.applyAsDouble(targetVariable);
            DoubleFormatUtils.formatDoubleFast(weight, 6, 6, sb);
        }

        var features = transformedAndHashed.asEnumMap();

        for (Map.Entry<H, HashedValue> entry : features.entrySet()) {
            var ns = entry.getKey().ordinal();
            checkState(ns < VALID_VW_NAMESPACE_CHARS.length,
                    "Sorry you cannot have more than " + VALID_VW_NAMESPACE_CHARS.length +
                            "namespaces for VW");

            char namespace = VALID_VW_NAMESPACE_CHARS[ns];
            sb.append(" |");
            sb.append(namespace);
            sb.append(" ");

            var indices = entry.getValue().getCategoricals();
            var values = entry.getValue().getNumericals();

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

    private static <IN extends Enum<IN> & RawNamespace> RawValue readField(DataRecord<IN, RawValue> record, String fieldName) {
        return record.asEnumMap().entrySet().stream().filter(p ->
                fieldName.equals(p.getKey().toString())
        ).map(Map.Entry::getValue).findFirst().orElseThrow(()-> new IllegalStateException("Cannot find: field" + fieldName));
    }


}
