package com.hotvect.integrationtest.demoui;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class DemoUiRankerFactory implements SimpleAlgorithmFactory<Ranker<JsonNode, JsonNode>> {
    @Override
    public Ranker<JsonNode, JsonNode> apply(Optional<JsonNode> hyperparameter) {
        return new Ranker<>() {
            @Override
            public RankingResponse<JsonNode> rank(RankingRequest<JsonNode, JsonNode> rankingRequest) {
                List<JsonNode> actions = rankingRequest.availableActions();
                List<JsonNode> sorted = new ArrayList<>(actions);
                sorted.sort(Comparator.comparing(DemoUiRankerFactory::actionIdOrEmpty));

                List<RankingDecision<JsonNode>> decisions = new ArrayList<>(sorted.size());
                for (int rank = 0; rank < sorted.size(); rank++) {
                    JsonNode action = sorted.get(rank);
                    String actionId = actionIdOrEmpty(action);
                    if (actionId.isBlank()) {
                        throw new IllegalArgumentException("Action missing required field: action_id");
                    }
                    double score = (double) (sorted.size() - rank);
                    decisions.add(RankingDecision.builder(actionId, rank, action).withScore(score).build());
                }
                return RankingResponse.newResponse(decisions);
            }
        };
    }

    private static String actionIdOrEmpty(JsonNode action) {
        if (action == null || !action.isObject()) {
            return "";
        }
        JsonNode id = action.get("action_id");
        if (id == null || !id.isTextual()) {
            return "";
        }
        return id.asText();
    }
}

