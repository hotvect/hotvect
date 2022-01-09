package com.eshioji.hotvect.api.algodefinition.ranking;

import com.eshioji.hotvect.api.codec.ranking.ExampleEncoder;
import com.eshioji.hotvect.api.vectorization.ranking.Vectorizer;

import java.util.function.BiFunction;

public interface ExampleEncoderFactory<SHARED, ACTION, OUTCOME> extends BiFunction<Vectorizer<SHARED, ACTION>, RewardFunction<OUTCOME>, ExampleEncoder<SHARED, ACTION, OUTCOME>> {
    @Override
    ExampleEncoder<SHARED, ACTION, OUTCOME> apply(Vectorizer<SHARED, ACTION> vectorizer, RewardFunction<OUTCOME> rewardFunction);
}
