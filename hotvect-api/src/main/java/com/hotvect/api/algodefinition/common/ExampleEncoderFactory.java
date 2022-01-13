package com.hotvect.api.algodefinition.common;

import com.hotvect.api.codec.common.ExampleEncoder;
import com.hotvect.api.data.common.Example;
import com.hotvect.api.vectorization.Vectorizer;

import java.util.function.BiFunction;

public interface ExampleEncoderFactory<EXAMPLE extends Example, VEC extends Vectorizer, OUTCOME> extends BiFunction<VEC, RewardFunction<OUTCOME>, ExampleEncoder<EXAMPLE>> {
    @Override
    ExampleEncoder<EXAMPLE> apply(VEC vectorizer, RewardFunction<OUTCOME> rewardFunction);
}
