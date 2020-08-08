package com.eshioji.hotvect.vw;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.raw.RawNamespace;
import com.eshioji.hotvect.api.data.raw.RawValue;
import com.eshioji.hotvect.core.util.DataRecordEncoder;
import com.eshioji.hotvect.core.vectorization.Vectorizer;

import java.util.Map;
import java.util.function.DoubleUnaryOperator;

public class VwInstanceEncoder<K extends Enum<K> & RawNamespace> implements DataRecordEncoder<K> {
    private final boolean binary;
    private final Vectorizer<K> vectorizer;
    private final DoubleUnaryOperator targetToImportanceWeight;

    public VwInstanceEncoder(Vectorizer<K> vectorizer) {
        this.vectorizer = vectorizer;
        this.binary = false;
        this.targetToImportanceWeight = null;
    }

    public VwInstanceEncoder(Vectorizer<K> vectorizer, boolean binary, DoubleUnaryOperator targetToImportanceWeight) {
        this.vectorizer = vectorizer;
        this.binary = binary;
        this.targetToImportanceWeight = targetToImportanceWeight;
    }


    public VwInstanceEncoder(Vectorizer<K> vectorizer, boolean binary) {
        this.vectorizer = vectorizer;
        this.binary = binary;
        this.targetToImportanceWeight = null;
    }

    private String vwEncode(
            DataRecord<K, RawValue> request,
            SparseVector vector,
            boolean binary,
            DoubleUnaryOperator targetToImportanceWeight) {
        int[] indices = vector.indices();
        double[] values = vector.values();

        StringBuilder sb = new StringBuilder();

        double targetVariable = readField(request, "target").getSingleNumerical();
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
        sb.append(" | ");

        for (int j = 0; j < indices.length; j++) {
            int feature = indices[j];
            double value = values[j];
            sb.append(feature);
            sb.append(':');
            DoubleFormatUtils.formatDoubleFast(value, 6, 6, sb);
            sb.append(" ");
        }

        return sb.toString();
    }

    private RawValue readField(DataRecord<K, RawValue> record, String fieldName) {
        return record.asEnumMap().entrySet().stream().filter(p ->
                fieldName.equals(p.getKey().toString())
        ).map(Map.Entry::getValue).findFirst().get();
    }

    @Override
    public String apply(DataRecord<K, RawValue> toEncode) {
        SparseVector vector = vectorizer.apply(toEncode);
        return vwEncode(toEncode, vector, binary, targetToImportanceWeight);
    }

}
