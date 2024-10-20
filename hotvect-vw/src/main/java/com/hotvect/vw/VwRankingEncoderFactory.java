package com.hotvect.vw;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.ranking.RankingExampleEncoderFactory;
import com.hotvect.api.algodefinition.ranking.RankingVectorizer;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;

public class VwRankingEncoderFactory<SHARED, ACTION, OUTCOME> implements RankingExampleEncoderFactory<RankingVectorizer<SHARED, ACTION>, SHARED, ACTION, OUTCOME> {
    @Override
    public RankingExampleEncoder<SHARED, ACTION, OUTCOME> apply(RankingVectorizer<SHARED, ACTION> rankingVectorizer, RewardFunction<OUTCOME> rewardFunction) {
        return new VwRankingEncoder<>(rankingVectorizer, rewardFunction);
    }
}
