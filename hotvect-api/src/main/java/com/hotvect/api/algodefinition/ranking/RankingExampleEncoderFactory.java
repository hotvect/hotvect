package com.hotvect.api.algodefinition.ranking;

import com.hotvect.api.algodefinition.common.ExampleEncoderFactory;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.api.data.ranking.RankingExample;

public interface RankingExampleEncoderFactory<DEPENDENCY, SHARED, ACTION, OUTCOME> extends ExampleEncoderFactory<RankingExample<SHARED, ACTION, OUTCOME>, DEPENDENCY, OUTCOME> {
    @Override
    RankingExampleEncoder<SHARED, ACTION, OUTCOME> apply(DEPENDENCY dependency, RewardFunction<OUTCOME> rewardFunction);
}
