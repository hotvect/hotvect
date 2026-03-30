package com.hotvect.catboost;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.ranking.BulkScorerFactory;
import com.hotvect.core.transform.ranking.StreamingRankingTransformer;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;

public class CatBoostStreamingBulkScorerFactory<SHARED, ACTION>
        implements BulkScorerFactory<StreamingRankingTransformer<SHARED, ACTION>, SHARED, ACTION> {
    @Override
    public BulkScorer<SHARED, ACTION> apply(
            StreamingRankingTransformer<SHARED, ACTION> rankingTransformer,
            Map<String, InputStream> parameters,
            Optional<JsonNode> hyperparameter
    ) {
        InputStream modelStream = CatBoostFactoryUtils.getModelStream(parameters);
        checkState(
                modelStream != null,
                "Missing CatBoost model parameters. Expected key '%s' (preferred) or '%s' (legacy). Available keys: %s",
                CatBoostFactoryUtils.MODEL_PARAMETER_KEY_V10,
                CatBoostFactoryUtils.MODEL_PARAMETER_KEY_V9,
                parameters.keySet()
        );
        HotvectCatBoostModel hotvectCatBoostModel = HotvectCatBoostModel.loadModel(modelStream);
        String taskType = CatBoostFactoryUtils.getTaskType(hyperparameter);

        return new CatBoostStreamingBulkScorer<>(
                rankingTransformer,
                hotvectCatBoostModel,
                taskType
        );
    }
}
