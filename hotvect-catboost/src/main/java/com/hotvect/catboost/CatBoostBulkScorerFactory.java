package com.hotvect.catboost;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.ranking.BulkScorerFactory;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.core.transform.ranking.StandardRankingTransformer;
import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;
import com.hotvect.utils.HyperparamUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public class CatBoostBulkScorerFactory<SHARED, ACTION> implements BulkScorerFactory<StandardRankingTransformer<SHARED, ACTION>, SHARED, ACTION> {
    private static final Logger log = LoggerFactory.getLogger(CatBoostBulkScorerFactory.class);

    @Override
    public BulkScorer<SHARED, ACTION> apply(StandardRankingTransformer<SHARED, ACTION> rankingTransformer, Map<String, InputStream> parameters, Optional<JsonNode> hyperparameter) {
        HotvectCatBoostModel hotvectCatBoostModel = HotvectCatBoostModel.loadModel(parameters.get("model.parameter"));
        int noforkThreshold = HyperparamUtils.getOrDefault(hyperparameter, JsonNode::asInt, 100, "catboost_scorer", "nofork_threshold");
        String taskType = HyperparamUtils.getOrDefault(hyperparameter, JsonNode::asText, "classification", "task_type");
        log.info("Using nofork threshold of {}", noforkThreshold);

        return new CatBoostBulkScorer<>(
                rankingTransformer,
                hotvectCatBoostModel,
                noforkThreshold,
                taskType
        );
    }
}
