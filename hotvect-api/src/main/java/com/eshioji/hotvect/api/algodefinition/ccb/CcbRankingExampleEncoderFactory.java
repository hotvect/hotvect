package com.eshioji.hotvect.api.algodefinition.ccb;

import com.eshioji.hotvect.api.algodefinition.common.ExampleEncoderFactory;
import com.eshioji.hotvect.api.algodefinition.common.RewardFunction;
import com.eshioji.hotvect.api.codec.ccb.CcbRankingExampleEncoder;
import com.eshioji.hotvect.api.codec.common.ExampleEncoder;
import com.eshioji.hotvect.api.data.ccb.CcbRankingExample;
import com.eshioji.hotvect.api.data.ranking.RankingExample;
import com.eshioji.hotvect.api.vectorization.RankingVectorizer;

import java.util.function.BiFunction;

public interface CcbRankingExampleEncoderFactory<SHARED, ACTION, OUTCOME> extends ExampleEncoderFactory<CcbRankingExample<SHARED, ACTION, OUTCOME>, RankingVectorizer<SHARED, ACTION>, OUTCOME> {
    @Override
    CcbRankingExampleEncoder<SHARED, ACTION, OUTCOME> apply(RankingVectorizer<SHARED, ACTION> rankingVectorizer, RewardFunction<OUTCOME> rewardFunction);
}
