package com.hotvect.api.algodefinition.ranking;

import com.hotvect.api.algodefinition.common.ExampleEncoderFactory;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.vectorization.RankingVectorizer;

public interface RankingExampleEncoderFactory<SHARED, ACTION, OUTCOME> extends ExampleEncoderFactory<RankingExample<SHARED, ACTION, OUTCOME>, RankingVectorizer<SHARED, ACTION>, OUTCOME> {
    @Override
    RankingExampleEncoder<SHARED, ACTION, OUTCOME> apply(RankingVectorizer<SHARED, ACTION> rankingVectorizer, RewardFunction<OUTCOME> rewardFunction);
}
