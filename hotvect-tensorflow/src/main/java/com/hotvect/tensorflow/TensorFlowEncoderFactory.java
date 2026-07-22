package com.hotvect.tensorflow;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.ranking.RankingExampleEncoderFactory;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;

/**
 * Factory for creating TFRecord ranking example encoders.
 * Follows the same pattern as CatBoostEncoderFactory for consistency.
 *
 * This factory creates encoders that return ByteBuffer in proper TFRecord format,
 * compatible with TensorFlow's tf.data.TFRecordDataset.
 */
public class TensorFlowEncoderFactory<SHARED, ACTION, OUTCOME>
        implements RankingExampleEncoderFactory<RankingTransformer<SHARED, ACTION>, SHARED, ACTION, OUTCOME> {

    @Override
    public RankingExampleEncoder<SHARED, ACTION, OUTCOME> apply(
            RankingTransformer<SHARED, ACTION> tensorFlowTransformer,
            RewardFunction<OUTCOME> rewardFunction) {

        // Validate parameters
        if (tensorFlowTransformer == null) {
            throw new NullPointerException("TensorFlow transformer cannot be null");
        }
        if (rewardFunction == null) {
            throw new NullPointerException("Reward function cannot be null");
        }

        // Return the TFRecord encoder directly - no adapter needed
        return new TFRecordRankingEncoder<>(tensorFlowTransformer, rewardFunction);
    }
}