package com.hotvect.integrationtest.model.iris;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.ranking.RankingExampleEncoderFactory;
import com.hotvect.api.algodefinition.ranking.RankingVectorizer;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;

import java.util.Map;

public class IrisEncoderFactory implements RankingExampleEncoderFactory<RankingVectorizer<String, Map<String, String>>, String, Map<String, String>, String> {
    @Override
    public RankingExampleEncoder<String, Map<String, String>, String> apply(RankingVectorizer<String, Map<String, String>> dependency, RewardFunction<String> rewardFunction) {
        return toEncode -> {
            var vectorized  = dependency.apply(toEncode.getRankingRequest());
            var reward = rewardFunction.applyAsDouble(toEncode.getOutcomes().get(0).getOutcome());
            return String.format("%s | %s", reward, vectorized);
        };
    }
}
