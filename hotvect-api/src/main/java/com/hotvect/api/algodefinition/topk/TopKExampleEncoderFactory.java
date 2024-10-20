package com.hotvect.api.algodefinition.topk;

import com.hotvect.api.algodefinition.common.ExampleEncoderFactory;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.codec.topk.TopKExampleEncoder;
import com.hotvect.api.data.topk.TopKExample;

public interface TopKExampleEncoderFactory<DEPENDENCY, SHARED, ACTION, OUTCOME> extends ExampleEncoderFactory<TopKExample<SHARED, ACTION, OUTCOME>, DEPENDENCY, OUTCOME> {
    @Override
    TopKExampleEncoder<SHARED, ACTION, OUTCOME> apply(DEPENDENCY dependency, RewardFunction<OUTCOME> rewardFunction);
}
