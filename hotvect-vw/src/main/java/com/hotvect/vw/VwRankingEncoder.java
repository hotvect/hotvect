package com.hotvect.vw;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.ranking.RankingVectorizer;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;
import com.hotvect.core.util.DoubleFormatUtils;

import java.util.Map;
import java.util.stream.Collectors;

public class VwRankingEncoder<SHARED, ACTION, OUTCOME> implements RankingExampleEncoder<SHARED, ACTION, OUTCOME> {
    private static final int FLOAT_FORMAT_PRECISION = 9;
    private final RankingVectorizer<SHARED, ACTION> vectorizer;
    private final RewardFunction<OUTCOME> rewardFunction;

    public VwRankingEncoder(RankingVectorizer<SHARED, ACTION> vectorizer, RewardFunction<OUTCOME> rewardFunction) {
        this.vectorizer = vectorizer;
        this.rewardFunction = rewardFunction;
    }

    @Override
    public String apply(RankingExample<SHARED, ACTION, OUTCOME> toEncode) {
        var actionVectors = vectorizer.apply(toEncode.getRankingRequest());
        Map<Integer, OUTCOME> actionIdx2Outcome = toEncode.getOutcomes().stream().collect(Collectors.toMap(
                x -> x.getRankingDecision().getActionIndex(),
                RankingOutcome::getOutcome
        ));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < actionVectors.size(); i++) {
            var actionVector = actionVectors.get(i);
            var outcome = actionIdx2Outcome.get(i);
            var reward = rewardFunction.applyAsDouble(outcome);

            sb.append(reward > 0 ? "1" : "-1");

            sb.append(" | ");
            // Numericals
            int[] numericalIndices = actionVector.getNumericalIndices();
            double[] numericalValues = actionVector.getNumericalValues();

            for (int j = 0; j < numericalIndices.length; j++) {
                int feature = numericalIndices[j];
                double value = numericalValues[j];
                sb.append(feature);
                sb.append(':');
                DoubleFormatUtils.format(value, FLOAT_FORMAT_PRECISION, sb);
                sb.append(" ");
            }

            // Categoricals
            int[] categoricalIndices = actionVector.getCategoricalIndices();
            for (int categoricalIndex : categoricalIndices) {
                sb.append(categoricalIndex);
                sb.append(':');
                sb.append('1');
                sb.append(" ");
            }

            if (i == actionVectors.size() - 1){
                // Last element, no need to add new line
            } else {
                sb.append('\n');
            }
        }

        return sb.toString();
    }
}

