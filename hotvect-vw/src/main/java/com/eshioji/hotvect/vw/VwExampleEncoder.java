package com.eshioji.hotvect.vw;

import com.eshioji.hotvect.api.codec.ExampleEncoder;
import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.raw.Example;
import com.eshioji.hotvect.core.vectorization.Vectorizer;

import java.util.function.DoubleUnaryOperator;

public class VwExampleEncoder<R> implements ExampleEncoder<R> {
    private final boolean binary;
    private final Vectorizer<R> vectorizer;
    private final DoubleUnaryOperator targetToImportanceWeight;

    public VwExampleEncoder(Vectorizer<R> vectorizer) {
        this.vectorizer = vectorizer;
        this.binary = false;
        this.targetToImportanceWeight = null;
    }

    public VwExampleEncoder(Vectorizer<R> vectorizer, boolean binary, DoubleUnaryOperator targetToImportanceWeight) {
        this.vectorizer = vectorizer;
        this.binary = binary;
        this.targetToImportanceWeight = targetToImportanceWeight;
    }


    public VwExampleEncoder(Vectorizer<R> vectorizer, boolean binary) {
        this.vectorizer = vectorizer;
        this.binary = binary;
        this.targetToImportanceWeight = null;
    }

    private String vwEncode(
            Example<R> request,
            SparseVector vector,
            boolean binary,
            DoubleUnaryOperator targetToImportanceWeight) {
        int[] indices = vector.indices();
        double[] values = vector.values();

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

    @Override
    public String apply(Example<R> toEncode) {
        SparseVector vector = vectorizer.apply(toEncode.getRecord());
        return vwEncode(toEncode, vector, binary, targetToImportanceWeight);
    }

}
