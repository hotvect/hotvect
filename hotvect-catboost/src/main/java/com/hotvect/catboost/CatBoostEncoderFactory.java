package com.hotvect.catboost;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.ranking.RankingExampleEncoderFactory;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.core.transform.ranking.ComputingRankingTransformer;

public class CatBoostEncoderFactory<SHARED, ACTION, OUTCOME> implements RankingExampleEncoderFactory<ComputingRankingTransformer<SHARED, ACTION>, SHARED, ACTION, OUTCOME> {
    @Override
    public RankingExampleEncoder<SHARED, ACTION, OUTCOME> apply(ComputingRankingTransformer<SHARED, ACTION> catboostTransformer, RewardFunction<OUTCOME> rewardFunction){
        return new CatBoostEncoder<>(catboostTransformer, rewardFunction);
    }
}
