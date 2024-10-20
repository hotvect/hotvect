package com.hotvect.api.algodefinition.common;

import com.hotvect.api.codec.common.ExampleEncoder;
import com.hotvect.api.data.common.Example;

import java.util.function.BiFunction;

public interface ExampleEncoderFactory<EXAMPLE extends Example, DEPENDENCY, OUTCOME> extends BiFunction<DEPENDENCY, RewardFunction<OUTCOME>, ExampleEncoder<EXAMPLE>> {
    @Override
    ExampleEncoder<EXAMPLE> apply(DEPENDENCY dependency, RewardFunction<OUTCOME> rewardFunction);
}
