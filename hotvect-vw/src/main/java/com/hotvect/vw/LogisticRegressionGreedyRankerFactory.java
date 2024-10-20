package com.hotvect.vw;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Charsets;
import com.hotvect.api.algodefinition.ranking.RankerFactory;
import com.hotvect.api.algodefinition.ranking.RankingVectorizer;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.core.rank.GreedyRanker;
import it.unimi.dsi.fastutil.ints.Int2DoubleMap;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Optional;

public class LogisticRegressionGreedyRankerFactory<SHARED, ACTION> implements RankerFactory<RankingVectorizer<SHARED, ACTION>, SHARED, ACTION> {
    @Override
    public Ranker<SHARED, ACTION> apply(RankingVectorizer<SHARED, ACTION> dependency, Map<String, InputStream> parameters, Optional<JsonNode> hyperparameter) {
        Int2DoubleMap params = (new VwModelImporter()).apply(new BufferedReader(new InputStreamReader(parameters.get("model.parameter"), Charsets.UTF_8)));
        LogisticRegressionEstimator estimator = new LogisticRegressionEstimator(0.0, params);
        return new GreedyRanker<>(dependency, estimator);
    }
}
