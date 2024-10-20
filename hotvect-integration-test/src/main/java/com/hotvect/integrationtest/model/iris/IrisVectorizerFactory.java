package com.hotvect.integrationtest.model.iris;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.scoring.ScoringVectorizer;
import com.hotvect.api.algodefinition.scoring.ScoringVectorizerFactory;
import com.hotvect.api.data.SparseVector;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class IrisVectorizerFactory implements ScoringVectorizerFactory<Map<String, String>> {
    @Override
    public ScoringVectorizer<Map<String, String>> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters) {
        return toVectorize -> {
            assertEquals(toVectorize.get("iris.model.vectorizer_test_parameter"), hyperparameters.get().get("vectorizer_test_parameter").asText());
            return new SparseVector(new int[]{1, 2});
        };
    }
}

