package com.hotvect.integrationtest.iris;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Streams;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.ranking.CompositeRankerFactory;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.Scorer;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;

import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class IrisRankerFactory implements CompositeRankerFactory<String, Map<String, String>> {
    @Override
    public Ranker<String, Map<String, String>> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters, Map<String, AlgorithmInstance<?>> dependencies) {
        var algoInstance =
                ((AlgorithmInstance<Scorer<Map<String, String>>>) dependencies.get("com-hotvect-test-iris-model"));
        Scorer<Map<String, String>> irisModel = algoInstance.getAlgorithm();

        return new Ranker<>() {
            @Override
            public RankingResponse<Map<String, String>> rank(RankingRequest<String, Map<String, String>> rankingRequest) {
                List<RankingDecision<Map<String, String>>> scored = Streams.mapWithIndex(rankingRequest.getAvailableActions().stream(), (from, index) -> {
                    assertEquals(from.get("iris.model.parameter.id"), algoInstance.getAlgorithmParameterMetadata().getParameterId());

                    var score = irisModel.applyAsDouble(from);
                    return RankingDecision.builder((int) index, from).withScore(score).build();
                }).sorted(Comparator.comparingDouble(RankingDecision::getScore)).collect(Collectors.toList());
                return RankingResponse.newResponse(scored);
            }
        };
    }
}
