package com.hotvect.integrationtest.demoui;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.ranking.CompositeRankerFactory;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DemoUiRankerFactory implements CompositeRankerFactory<JsonNode, JsonNode> {
    @Override
    public Ranker<JsonNode, JsonNode> apply(
            Optional<JsonNode> configuration,
            Map<String, InputStream> parameters,
            Map<String, AlgorithmInstance<?>> dependencies) {
        return new Ranker<>() {
            @Override
            public RankingResponse<JsonNode> rank(RankingRequest<JsonNode, JsonNode> rankingRequest) {
                var actions = rankingRequest.actions();
                List<ActionWithId> sorted = new ArrayList<>(actions.size());
                for (int i = 0; i < actions.size(); i++) {
                    var action = actions.get(i);
                    sorted.add(new ActionWithId(i, action.actionId(), action.action()));
                }
                sorted.sort(Comparator.comparing(ActionWithId::actionId));

                List<RankingDecision<JsonNode>> decisions = new ArrayList<>(sorted.size());
                for (int rank = 0; rank < sorted.size(); rank++) {
                    ActionWithId action = sorted.get(rank);
                    double score = (double) (sorted.size() - rank);
                    decisions.add(
                            RankingDecision.builder(action.actionId(), action.actionIndex(), action.action())
                                    .withScore(score)
                                    .build()
                    );
                }
                return RankingResponse.newResponse(decisions);
            }
        };
    }

    private record ActionWithId(int actionIndex, String actionId, JsonNode action) {
    }
}
