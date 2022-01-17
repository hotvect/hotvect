package com.hotvect.offlineutils.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algorithms.Scorer;
import com.hotvect.api.data.scoring.ScoringExample;

import java.util.function.BiFunction;
import java.util.function.Function;

public class ScoringResultFormatter<RECORD, OUTCOME> implements BiFunction<RewardFunction<OUTCOME>, Scorer<RECORD>, Function<ScoringExample<RECORD, OUTCOME>, String>> {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Function<ScoringExample<RECORD, OUTCOME>, String> apply(RewardFunction<OUTCOME> rewardFunction, Scorer<RECORD> scorer) {
        return ex -> {
            var root = objectMapper.createObjectNode();
            var exampleId = ex.getExampleId();
            if(exampleId != null){
                root.put("example_id", exampleId);
            }
            var reward = rewardFunction.applyAsDouble(ex.getOutcome());
            var score = scorer.applyAsDouble(ex.getRecord());
            root.put("reward", reward);
            root.put("score", score);
            try {
                return objectMapper.writeValueAsString(root);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        };
    }
}
