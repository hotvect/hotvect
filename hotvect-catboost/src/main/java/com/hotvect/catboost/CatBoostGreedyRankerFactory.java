package com.hotvect.catboost;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.ranking.RankerFactory;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.transformation.ranking.MemoizableRankingTransformer;
import com.hotvect.core.rank.BulkScoreGreedyRanker;
import com.hotvect.utils.HyperparamUtils;
import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public class CatBoostGreedyRankerFactory<SHARED, ACTION> implements RankerFactory<MemoizableRankingTransformer<SHARED, ACTION>, SHARED, ACTION> {
    private static final Logger log = LoggerFactory.getLogger(CatBoostGreedyRankerFactory.class);
    @Override
    public Ranker<SHARED, ACTION> apply(MemoizableRankingTransformer<SHARED, ACTION> catBoostRankingTransformer, Map<String, InputStream> parameters, Optional<JsonNode> hyperparameter) {
        HotvectCatBoostModel hotvectCatBoostModel = HotvectCatBoostModel.loadModel(parameters.get("model.parameter"));
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
