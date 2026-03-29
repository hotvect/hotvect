package com.hotvect.catboost;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.ranking.BulkScorerFactory;
import com.hotvect.core.transform.ranking.StreamingRankingTransformer;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;
import com.hotvect.utils.HyperparamUtils;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public class CatBoostStreamingBulkScorerFactory<SHARED, ACTION>
        implements BulkScorerFactory<StreamingRankingTransformer<SHARED, ACTION>, SHARED, ACTION> {
    @Override
    public BulkScorer<SHARED, ACTION> apply(
            StreamingRankingTransformer<SHARED, ACTION> rankingTransformer,
            Map<String, InputStream> parameters,
            Optional<JsonNode> hyperparameter
    ) {
        HotvectCatBoostModel hotvectCatBoostModel = HotvectCatBoostModel.loadModel(parameters.get("model.parameter"));
        String taskType = HyperparamUtils.getOrDefault(hyperparameter, JsonNode::asText, "classification", "task_type");

        return new CatBoostStreamingBulkScorer<>(
                rankingTransformer,
                hotvectCatBoostModel,
                taskType
        );
    }
}

