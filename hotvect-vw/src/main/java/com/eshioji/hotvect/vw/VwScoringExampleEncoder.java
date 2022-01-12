package com.eshioji.hotvect.vw;

import com.eshioji.hotvect.api.codec.scoring.ScoringExampleEncoder;
import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.scoring.ScoringExample;
import com.eshioji.hotvect.api.vectorization.ScoringVectorizer;

import java.util.function.DoubleUnaryOperator;

public class VwScoringExampleEncoder<RECORD> implements ScoringExampleEncoder<RECORD> {
    private final boolean binary;
    private final ScoringVectorizer<RECORD> scoringVectorizer;
    private final DoubleUnaryOperator targetToImportanceWeight;

    public VwScoringExampleEncoder(ScoringVectorizer<RECORD> scoringVectorizer) {
        this.scoringVectorizer = scoringVectorizer;
        this.binary = false;
        this.targetToImportanceWeight = null;
    }

    public VwScoringExampleEncoder(ScoringVectorizer<RECORD> scoringVectorizer, boolean binary, DoubleUnaryOperator targetToImportanceWeight) {
        this.scoringVectorizer = scoringVectorizer;
        this.binary = binary;
        this.targetToImportanceWeight = targetToImportanceWeight;
    }


    public VwScoringExampleEncoder(ScoringVectorizer<RECORD> scoringVectorizer, boolean binary) {
        this.scoringVectorizer = scoringVectorizer;
        this.binary = binary;
        this.targetToImportanceWeight = null;
    }

    private String vwEncode(
            ScoringExample<RECORD> request,
            SparseVector vector,
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
        sb.append(" | ");

        // Numericals
        int[] numericalIndices = vector.getNumericalIndices();
        double[] numericalValues = vector.getNumericalValues();

        for (int j = 0; j < numericalIndices.length; j++) {
            int feature = numericalIndices[j];
            double value = numericalValues[j];
            sb.append(feature);
            sb.append(':');
            DoubleFormatUtils.formatDoubleFast(value, 6, 6, sb);
            sb.append(" ");
        }

        // Categoricals
        int[] categoricalIndices = vector.getCategoricalIndices();
        for (int categoricalIndex : categoricalIndices) {
            sb.append(categoricalIndex);
            sb.append(':');
            sb.append('1');
            sb.append(" ");
        }

        return sb.toString();
    }

    @Override
    public String apply(ScoringExample<RECORD> toEncode) {
        SparseVector vector = scoringVectorizer.apply(toEncode.getRecord());
        return vwEncode(toEncode, vector, binary, targetToImportanceWeight);
    }

}
