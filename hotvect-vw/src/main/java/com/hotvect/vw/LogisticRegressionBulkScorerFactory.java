package com.hotvect.vw;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Charsets;
import com.hotvect.api.algodefinition.ranking.BulkScorerFactory;
import com.hotvect.api.algorithms.BulkScorer;
import com.hotvect.api.transformation.ranking.MemoizableRankingVectorizer;
import com.hotvect.utils.HyperparamUtils;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Optional;

public class LogisticRegressionBulkScorerFactory<SHARED, ACTION> implements BulkScorerFactory<MemoizableRankingVectorizer<SHARED, ACTION>, SHARED, ACTION> {
    private static final Logger log = LoggerFactory.getLogger(LogisticRegressionBulkScorerFactory.class);

    @Override
    public BulkScorer<SHARED, ACTION> apply(MemoizableRankingVectorizer<SHARED, ACTION> dependency, Map<String, InputStream> parameters, Optional<JsonNode> hyperparameter) {
        int noforkThreshold = HyperparamUtils.getOrDefault(hyperparameter, JsonNode::asInt, 100, "vw_scorer", "nofork_threshold");
        log.info("Using nofork threshold of {}", noforkThreshold);
        Int2DoubleMap params = (new VwModelImporter()).apply(new BufferedReader(new InputStreamReader(parameters.get("model.parameter"), Charsets.UTF_8)));
        LogisticRegressionEstimator estimator = new LogisticRegressionEstimator(0.0, params);
        return new LogisticRegressionBulkScorer<>(dependency, estimator, noforkThreshold);
    }
}
