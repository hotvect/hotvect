package com.hotvect.api.algodefinition.ranking;

import com.hotvect.api.algodefinition.common.ExampleEncoderFactory;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.api.data.ranking.RankingExample;

public interface RankingExampleEncoderFactory<DEPENDENCY, SHARED, ACTION, OUTCOME> extends ExampleEncoderFactory<RankingExample<SHARED, ACTION, OUTCOME>, DEPENDENCY, OUTCOME> {
    /**
     * @deprecated Use create(...).
     */
    @Deprecated(forRemoval = true, since = "10.40.0")
    @Override
    RankingExampleEncoder<SHARED, ACTION, OUTCOME> apply(DEPENDENCY dependency, RewardFunction<OUTCOME> rewardFunction);

    @SuppressWarnings("unchecked")
    @Override
    default RankingExampleEncoder<SHARED, ACTION, OUTCOME> create(DEPENDENCY dependency, RewardFunction<OUTCOME> rewardFunction) {
        return (RankingExampleEncoder<SHARED, ACTION, OUTCOME>) ExampleEncoderFactory.super.create(dependency, rewardFunction);
    }
}
