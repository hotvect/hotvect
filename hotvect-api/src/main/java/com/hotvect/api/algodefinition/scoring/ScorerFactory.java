package com.hotvect.api.algodefinition.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.common.NonCompositeAlgorithmFactory;
import com.hotvect.api.algorithms.Scorer;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;


public interface ScorerFactory<RECORD> extends NonCompositeAlgorithmFactory<ScoringVectorizer<RECORD>, Scorer<RECORD>> {
    @Override
    Scorer<RECORD> apply(ScoringVectorizer<RECORD> dependency, Map<String, InputStream> parameters, Optional<JsonNode> hyperparameter);
}
