package com.hotvect.integrationtest.demoui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.hotvect.api.algodefinition.ranking.RankingExampleDecoderFactory;
import com.hotvect.api.codec.ranking.RankingExampleDecoder;
import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.FeatureStoreResponseContainer;
import com.hotvect.api.data.ranking.OfflineRankingRequest;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.RankingOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DemoUiRankingDecoderFactory implements RankingExampleDecoderFactory<JsonNode, JsonNode, JsonNode> {
    private static final ObjectMapper OM = new ObjectMapper();

    @Override
    public RankingExampleDecoder<JsonNode, JsonNode, JsonNode> apply(Optional<JsonNode> hyperparameter) {
        return toDecode -> {
            JsonNode root;
            try {
                root = OM.readTree(toDecode);
            } catch (Exception e) {
                throw new IllegalArgumentException("Example must be valid JSON object: " + e.getMessage(), e);
            }
            if (!(root instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) {
                throw new IllegalArgumentException("Example JSON must be an object");
            }

            String exampleId = nonEmptyText(obj.get("example_id"), "example_id");
            JsonNode shared = obj.get("shared");
            if (shared == null) {
                shared = OM.getNodeFactory().nullNode();
            }

            JsonNode actionsNode = obj.get("actions");
            if (!(actionsNode instanceof ArrayNode actionsArr)) {
                throw new IllegalArgumentException("Missing required field: actions (must be an array)");
            }

            List<AvailableAction<JsonNode>> actions = new ArrayList<>(actionsArr.size());
            for (JsonNode a : actionsArr) {
                if (!a.isObject()) {
                    throw new IllegalArgumentException("Each action must be an object");
                }
                String actionId = nonEmptyText(a.get("action_id"), "actions[].action_id");
                actions.add(AvailableAction.of(actionId, a));
            }

            var req = OfflineRankingRequest.ofAvailableActions(exampleId, shared, actions, FeatureStoreResponseContainer.empty());
            return List.of(new RankingExample<>(exampleId, req, List.<RankingOutcome<JsonNode, JsonNode>>of()));
        };
    }

    private static String nonEmptyText(JsonNode node, String field) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException("Missing/blank required field: " + field);
        }
        return node.asText();
    }
}
