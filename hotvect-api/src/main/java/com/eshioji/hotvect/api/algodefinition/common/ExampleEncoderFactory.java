package com.eshioji.hotvect.api.algodefinition.common;

import com.eshioji.hotvect.api.codec.common.ExampleEncoder;
import com.eshioji.hotvect.api.data.common.Example;
import com.eshioji.hotvect.api.vectorization.Vectorizer;

import java.util.function.BiFunction;

public interface ExampleEncoderFactory<EXAMPLE extends Example, VEC extends Vectorizer, OUTCOME> extends BiFunction<VEC, RewardFunction<OUTCOME>, ExampleEncoder<EXAMPLE>> {
    @Override
    ExampleEncoder<EXAMPLE> apply(VEC vectorizer, RewardFunction<OUTCOME> rewardFunction);
}
