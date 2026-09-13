package com.hotvect.integrationtest.iris;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.reflect.TypeToken;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import com.hotvect.api.algodefinition.ranking.CompositeRankerFactory;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.Scorer;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.execution.ExecutionContext;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class IrisRankerFactory implements CompositeRankerFactory<String, Map<String, String>> {
    @Override
    public Ranker<String, Map<String, String>> create(
            ExecutionContext executionContext,
            Optional<LocalStateStorage> localStateStorage,
            Optional<JsonNode> hyperparameters,
            Map<String, InputStream> parameters,
            AlgorithmDependencies dependencies) {
        var irisModelInstance = dependencies.asMap().get("com-hotvect-test-iris-model");
        Scorer<Map<String, String>> irisModel = dependencies.only(
                "com-hotvect-test-iris-model",
                new TypeToken<Scorer<Map<String, String>>>() {});

        return new Ranker<>() {
            @Override
            public RankingResponse<Map<String, String>> rank(RankingRequest<String, Map<String, String>> rankingRequest) {
                var actions = rankingRequest.actions();
                List<RankingDecision<Map<String, String>>> scored = new ArrayList<>(actions.size());
                for (int i = 0; i < actions.size(); i++) {
                    var action = actions.get(i);
                    var rawAction = action.action();
                    assertEquals(
                            rawAction.get("iris.model.parameter.id"),
                            irisModelInstance.algorithmParameterMetadata().parameterId());
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
