package com.hotvect.catboost;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.ranking.RankerFactory;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.core.rank.BulkScoreGreedyRanker;
import com.hotvect.core.transform.ranking.ComputingRankingTransformer;
import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;
import com.hotvect.utils.HyperparamUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;

public class CatBoostGreedyRankerFactory<SHARED, ACTION> implements RankerFactory<ComputingRankingTransformer<SHARED, ACTION>, SHARED, ACTION> {
    private static final Logger log = LoggerFactory.getLogger(CatBoostGreedyRankerFactory.class);
    private static final String MODEL_PARAMETER_KEY = "model_parameter/model.parameter";
    @Override
    public Ranker<SHARED, ACTION> apply(ComputingRankingTransformer<SHARED, ACTION> catBoostRankingTransformer, Map<String, InputStream> parameters, Optional<JsonNode> hyperparameter) {
        InputStream modelStream = parameters.get(MODEL_PARAMETER_KEY);
        checkState(
                modelStream != null,
                "Missing CatBoost model parameters. Expected key '%s'. Available keys: %s",
                MODEL_PARAMETER_KEY,
                parameters.keySet()
        );
        HotvectCatBoostModel hotvectCatBoostModel = HotvectCatBoostModel.loadModel(modelStream);
        int noforkThreshold = HyperparamUtils.getOrDefault(hyperparameter, JsonNode::asInt, 100, "catboost_scorer", "nofork_threshold");
        String taskType = HyperparamUtils.getOrDefault(hyperparameter, JsonNode::asText, "classification", "task_type");
        log.info("Using nofork threshold of {}", noforkThreshold);
        BulkScorer<SHARED, ACTION> bulkScorer = new CatBoostBulkScorer<>(
                catBoostRankingTransformer,
                hotvectCatBoostModel,
                noforkThreshold,
                taskType
        );
        return new BulkScoreGreedyRanker<>(bulkScorer);

    }
}
