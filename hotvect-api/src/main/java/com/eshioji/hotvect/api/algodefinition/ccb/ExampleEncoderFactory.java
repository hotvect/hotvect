package com.eshioji.hotvect.api.algodefinition.ccb;

import com.eshioji.hotvect.api.codec.ccb.ExampleEncoder;
import com.eshioji.hotvect.api.vectorization.ccb.ActionVectorizer;

import java.util.function.BiFunction;

public interface ExampleEncoderFactory<SHARED, ACTION, OUTCOME> extends BiFunction<ActionVectorizer<SHARED, ACTION>, RewardFunction<OUTCOME>, ExampleEncoder<SHARED, ACTION, OUTCOME>> {
    @Override
    ExampleEncoder<SHARED, ACTION, OUTCOME> apply(ActionVectorizer<SHARED, ACTION> vectorizer, RewardFunction<OUTCOME> rewardFunction);
}
