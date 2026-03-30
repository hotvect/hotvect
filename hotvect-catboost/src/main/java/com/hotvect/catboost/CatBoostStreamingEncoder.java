package com.hotvect.catboost;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.core.transform.ranking.StreamingRankingTransformer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Encodes {@link RankingExample}s for CatBoost training using a {@link StreamingRankingTransformer}.
 *
 * <p>Unlike {@link CatBoostEncoder}, this encoder does not require {@code ComputingRankingTransformer}
 * semantics ({@code prepare(...)}). It relies on {@link StreamingRankingTransformer#transformStream}.</p>
 */
public class CatBoostStreamingEncoder<SHARED, ACTION, OUTCOME> implements RankingExampleEncoder<SHARED, ACTION, OUTCOME> {
    private final StreamingRankingTransformer<SHARED, ACTION> transformer;
    private final RewardFunction<OUTCOME> rewardFunction;

    public CatBoostStreamingEncoder(
            StreamingRankingTransformer<SHARED, ACTION> transformer,
            RewardFunction<OUTCOME> rewardFunction
    ) {
        this.transformer = transformer;
        CatBoostEncodingUtils.validateUsedFeatures(transformer.getUsedFeatures());
        this.rewardFunction = rewardFunction;
    }

    @Override
    public Optional<String> schemaDescription() {
        return Optional.of(new CatBoostColumnDescriptionGenerator().apply(this.transformer));
    }

    @Override
    public String encodedFileExtension() {
        return ".tsv";
    }

    @Override
    public ByteBuffer apply(RankingExample<SHARED, ACTION, OUTCOME> toEncode) {
        List<TransformedAction<ACTION>> transformedActions = transformer.transformStream(toEncode.request()).toList();
        List<RankingOutcome<OUTCOME, ACTION>> outcomes = toEncode.outcomes();
        checkArgument(
                transformedActions.size() == outcomes.size(),
                "Transformer output size (%s) must match outcome size (%s)",
                transformedActions.size(),
                outcomes.size()
        );

        return CatBoostEncodingUtils.encodeRows(
                transformedActions,
                outcomes,
                transformer.getUsedFeatures(),
                rewardFunction
        );
    }

    public static final String MISSING_CATEGORICAL = CatBoostEncodingUtils.MISSING_CATEGORICAL;
    public static final String MISSING_NUMERICAL = CatBoostEncodingUtils.MISSING_NUMERICAL;
    public static final String MISSING_TEXT = CatBoostEncodingUtils.MISSING_TEXT;
}
