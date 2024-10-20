package com.hotvect.integrationtest.model.iris;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.scoring.ScorerFactory;
import com.hotvect.api.algodefinition.scoring.ScoringVectorizer;
import com.hotvect.api.algorithms.Scorer;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public class IrisScorerFactory implements ScorerFactory<Map<String, String>> {
    @Override
    public Scorer<Map<String, String>> apply(ScoringVectorizer<Map<String, String>> dependency, Map<String, InputStream> parameters, Optional<JsonNode> hyperparameter) {
        assert parameters.containsKey("model.parameter");
        return stringDoubleMap -> {
            var vec = dependency.apply(stringDoubleMap);
            return 0.0;
        };
    }
}
