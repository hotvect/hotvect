package com.hotvect.core.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.api.data.ranking.RankingExample;

import java.util.Map;

import static java.util.stream.Collectors.toMap;

public class RankingExampleJsonEncoder<SHARED, ACTION, OUTCOME> implements RankingExampleEncoder<SHARED, ACTION, OUTCOME> {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final RankingTransformer<SHARED, ACTION> rankingTransformer;
    private final RewardFunction<OUTCOME> rewardFunction;

    public RankingExampleJsonEncoder(RankingTransformer<SHARED, ACTION> rankingTransformer, RewardFunction<OUTCOME> rewardFunction) {
        this.rankingTransformer = rankingTransformer;
        this.rewardFunction = rewardFunction;
    }

    @Override
    public String apply(RankingExample<SHARED, ACTION, OUTCOME> toEncode) {
        var root = objectMapper.createObjectNode();
        root.put("example_id", toEncode.getExampleId());

        ArrayNode actionToEncoded = objectMapper.createArrayNode();
        var actions = toEncode.getRankingRequest().getAvailableActions();
        var transformeds = rankingTransformer.apply(toEncode.getRankingRequest());
        Map<Integer, Double> rewards = toEncode.getOutcomes().stream().collect(toMap(
                outcomeRankingOutcome -> outcomeRankingOutcome.getRankingDecision().getActionIndex(),
                o -> rewardFunction.applyAsDouble(o.getOutcome())
        ));

        for (int i = 0; i < actions.size(); i++) {
            var result = objectMapper.createObjectNode();
            result.put("action_index", i);
            result.put("reward", rewards.get(i));

            var transformed = transformeds.get(i);
            throw new UnsupportedOperationException("not yet supported");
//            Map<String, Object> pojonized = DataRecords.pojonize(transformed);
//            JsonNode features = objectMapper.valueToTree(pojonized);
//
//            result.set("features", features);
//            actionToEncoded.add(result);
        }

        root.set("actions", actionToEncoded);

        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
