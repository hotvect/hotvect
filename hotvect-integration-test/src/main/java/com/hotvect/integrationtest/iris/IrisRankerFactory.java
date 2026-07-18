package com.hotvect.integrationtest.iris;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.ranking.CompositeRankerFactory;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.Scorer;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class IrisRankerFactory implements CompositeRankerFactory<String, Map<String, String>> {
    @Override
    public Ranker<String, Map<String, String>> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters, Map<String, AlgorithmInstance<?>> dependencies) {
        var algoInstance =
                ((AlgorithmInstance<Scorer<Map<String, String>>>) dependencies.get("com-hotvect-test-iris-model"));
        Scorer<Map<String, String>> irisModel = algoInstance.algorithm();

        return new Ranker<>() {
            @Override
            public RankingResponse<Map<String, String>> rank(RankingRequest<String, Map<String, String>> rankingRequest) {
                var actions = rankingRequest.actions();
                List<RankingDecision<Map<String, String>>> scored = new ArrayList<>(actions.size());
                for (int i = 0; i < actions.size(); i++) {
                    var action = actions.get(i);
                    var rawAction = action.action();
                    assertEquals(rawAction.get("iris.model.parameter.id"), algoInstance.algorithmParameterMetadata().parameterId());
                    var score = irisModel.applyAsDouble(rawAction);
                    scored.add(
                            RankingDecision.builder(action.actionId(), i, rawAction)
                                    .withScore(score)
                                    .build()
                    );
                }
                scored.sort(Comparator.comparingDouble(RankingDecision::score));
                return RankingResponse.newResponse(scored);
            }
        };
    }
}
