package com.eshioji.hotvect.api.algodefinition.ranking;

import com.eshioji.hotvect.api.algodefinition.common.ExampleEncoderFactory;
import com.eshioji.hotvect.api.algodefinition.common.RewardFunction;
import com.eshioji.hotvect.api.codec.common.ExampleEncoder;
import com.eshioji.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.eshioji.hotvect.api.data.ranking.RankingExample;
import com.eshioji.hotvect.api.vectorization.RankingVectorizer;

import java.util.function.BiFunction;

public interface RankingExampleEncoderFactory<SHARED, ACTION, OUTCOME> extends ExampleEncoderFactory<RankingExample<SHARED, ACTION, OUTCOME>, RankingVectorizer<SHARED, ACTION>, OUTCOME> {
    @Override
    RankingExampleEncoder<SHARED, ACTION, OUTCOME> apply(RankingVectorizer<SHARED, ACTION> rankingVectorizer, RewardFunction<OUTCOME> rewardFunction);
}
