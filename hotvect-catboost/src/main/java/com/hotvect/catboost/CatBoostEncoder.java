package com.hotvect.catboost;

import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.TransformedAction;
import com.hotvect.core.transform.ranking.ComputingRankingRequest;
import com.hotvect.core.transform.ranking.ComputingRankingTransformer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

public class CatBoostEncoder<SHARED, ACTION, OUTCOME> implements RankingExampleEncoder<SHARED, ACTION, OUTCOME> {
    private final ComputingRankingTransformer<SHARED, ACTION> transformer;
    private final RewardFunction<OUTCOME> rewardFunction;

    public CatBoostEncoder(ComputingRankingTransformer<SHARED, ACTION> transformer, RewardFunction<OUTCOME> rewardFunction) {
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
        ComputingRankingRequest<SHARED, ACTION> memoized = transformer.prepare(toEncode.request());
        List<TransformedAction<ACTION>> transformedActions = transformer.transform(memoized);
        return CatBoostEncodingUtils.encodeRows(
                transformedActions,
                toEncode.outcomes(),
                transformer.getUsedFeatures(),
                rewardFunction
        );
    }

    public static final String MISSING_CATEGORICAL = CatBoostEncodingUtils.MISSING_CATEGORICAL;
    public static final String MISSING_NUMERICAL = CatBoostEncodingUtils.MISSING_NUMERICAL;
    public static final String MISSING_TEXT = CatBoostEncodingUtils.MISSING_TEXT;
}
