package com.hotvect.catboost;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.ranking.RankingExampleEncoderFactory;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.core.transform.ranking.StandardRankingTransformer;

public class CatBoostEncoderFactory<SHARED, ACTION, OUTCOME> implements RankingExampleEncoderFactory<StandardRankingTransformer<SHARED, ACTION>, SHARED, ACTION, OUTCOME> {
    @Override
    public RankingExampleEncoder<SHARED, ACTION, OUTCOME> apply(StandardRankingTransformer<SHARED, ACTION> catboostTransformer, RewardFunction<OUTCOME> rewardFunction){
        return new CatBoostEncoder<>(catboostTransformer, rewardFunction);
    }
}

