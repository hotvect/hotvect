package com.hotvect.catboost;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.ranking.RankingExampleEncoderFactory;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.core.transform.ranking.StreamingRankingTransformer;

/**
 * CatBoost encoder factory for {@link StreamingRankingTransformer}s.
 *
 * <p>This is required for algorithm definitions that use a streaming transformer (e.g. generated via
 * {@code @GenerateSimpleRankingTransformer}) while still training CatBoost models.</p>
 */
public class CatBoostStreamingEncoderFactory<SHARED, ACTION, OUTCOME>
        implements RankingExampleEncoderFactory<StreamingRankingTransformer<SHARED, ACTION>, SHARED, ACTION, OUTCOME> {
    @Override
    public RankingExampleEncoder<SHARED, ACTION, OUTCOME> apply(
            StreamingRankingTransformer<SHARED, ACTION> transformer,
            RewardFunction<OUTCOME> rewardFunction
    ) {
        return new CatBoostStreamingEncoder<>(transformer, rewardFunction);
    }
}

