package com.hotvect.vw;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.codec.scoring.ScoringExampleEncoder;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.scoring.ScoringExample;
import com.hotvect.api.vectorization.ScoringVectorizer;

import java.util.function.ToDoubleFunction;

public class VwScoringExampleEncoder<RECORD, OUTCOME> implements ScoringExampleEncoder<RECORD, OUTCOME> {
    private final boolean binary;
    private final ScoringVectorizer<RECORD> scoringVectorizer;
    private final RewardFunction<OUTCOME> rewardFunction;
    private final ToDoubleFunction<OUTCOME> outcomeToimportanceWeight;

    public VwScoringExampleEncoder(ScoringVectorizer<RECORD> scoringVectorizer, RewardFunction<OUTCOME> rewardFunction) {
        this.scoringVectorizer = scoringVectorizer;
        this.binary = false;
        this.rewardFunction = rewardFunction;
        this.outcomeToimportanceWeight = null;
    }

    public VwScoringExampleEncoder(ScoringVectorizer<RECORD> scoringVectorizer, RewardFunction<OUTCOME> rewardFunction, boolean binary, ToDoubleFunction<OUTCOME> outcomeToimportanceWeight) {
        this.scoringVectorizer = scoringVectorizer;
        this.binary = binary;
        this.rewardFunction = rewardFunction;
        this.outcomeToimportanceWeight = outcomeToimportanceWeight;
    }


    public VwScoringExampleEncoder(ScoringVectorizer<RECORD> scoringVectorizer, RewardFunction<OUTCOME> rewardFunction, boolean binary) {
        this.scoringVectorizer = scoringVectorizer;
        this.binary = binary;
        this.rewardFunction = rewardFunction;
        this.outcomeToimportanceWeight = null;
    }

    private String vwEncode(
            ScoringExample<RECORD, OUTCOME> example,
            SparseVector vector,
            boolean binary) {

        StringBuilder sb = new StringBuilder();

        double targetVariable = rewardFunction.applyAsDouble(example.getOutcome());
        if (binary) {
            sb.append(targetVariable > 0 ? "1" : "-1");
        } else {
            DoubleFormatUtils.formatDoubleFast(targetVariable, 6, 6, sb);
        }

        if (this.outcomeToimportanceWeight != null) {
            sb.append(" ");
            double weight = this.outcomeToimportanceWeight.applyAsDouble(example.getOutcome());
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
    public String apply(ScoringExample<RECORD, OUTCOME> toEncode) {
        SparseVector vector = scoringVectorizer.apply(toEncode.getRecord());
        return vwEncode(toEncode, vector, binary);
    }
}
