package com.hotvect.integrationtest.model.iris;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.ranking.RankingExampleEncoderFactory;
import com.hotvect.api.algodefinition.ranking.RankingVectorizer;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class IrisEncoderFactory implements RankingExampleEncoderFactory<RankingVectorizer<String, Map<String, String>>, String, Map<String, String>, String> {
    @Override
    public RankingExampleEncoder<String, Map<String, String>, String> apply(RankingVectorizer<String, Map<String, String>> dependency, RewardFunction<String> rewardFunction) {
        return new RankingExampleEncoder<String, Map<String, String>, String>() {
            @Override
            public String encodedFileExtension() {
                return ".txt";
            }

            @Override
            public ByteBuffer apply(com.hotvect.api.data.ranking.RankingExample<String, Map<String, String>, String> toEncode) {
                var vectorized = dependency.apply(toEncode.request());
                var reward = rewardFunction.applyAsDouble(toEncode.outcomes().get(0).outcome());
                return ByteBuffer.wrap(String.format("%s | %s", reward, vectorized).getBytes(StandardCharsets.UTF_8));
            }
        };
    }
}
