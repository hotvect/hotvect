package com.hotvect.catboost;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.ranking.RankingExampleEncoderFactory;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.core.transform.ranking.MemoizingRankingTransformer;

public class CatBoostEncoderFactory<SHARED, ACTION, OUTCOME> implements RankingExampleEncoderFactory<MemoizingRankingTransformer<SHARED, ACTION>, SHARED, ACTION, OUTCOME> {
    @Override
    public RankingExampleEncoder<SHARED, ACTION, OUTCOME> apply(MemoizingRankingTransformer<SHARED, ACTION> catboostTransformer, RewardFunction<OUTCOME> rewardFunction){
        return new CatBoostEncoder<>(catboostTransformer, rewardFunction);
    }
}

