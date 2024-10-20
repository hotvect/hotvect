package com.hotvect.offlineutils.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingExample;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkState;
import static com.hotvect.utils.AdditionalProperties.getAdditionalProperties;
import static com.hotvect.utils.AdditionalProperties.mergeAdditionalProperties;

public class RankingResultFormatter<SHARED, ACTION, OUTCOME> implements BiFunction<RewardFunction<OUTCOME>, Ranker<SHARED, ACTION>, Function<RankingExample<SHARED, ACTION, OUTCOME>, String>> {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Function<RankingExample<SHARED, ACTION, OUTCOME>, String> apply(RewardFunction<OUTCOME> rewardFunction, Ranker<SHARED, ACTION> ranker) {
        return ex -> {
            var rankResult = ranker.rank(ex.getRankingRequest());
            var decisions = new ArrayList<>(rankResult.getRankingDecisions());
            Map<Integer, Integer> actionIdxToRank = toActionIdxToRank(decisions);

            // We want to keep the original order when we write the results
            decisions.sort(Comparator.comparingInt(RankingDecision::getActionIndex));

            ObjectNode root = objectMapper.createObjectNode();
            root.put("example_id", ex.getExampleId());
            Map<String, Object> sharedAdditionalProperties = new HashMap<>();
            sharedAdditionalProperties.putAll(rankResult.getAdditionalProperties());
            sharedAdditionalProperties.putAll(getAdditionalProperties(ex.getRankingRequest().getShared()));
            if (!sharedAdditionalProperties.isEmpty()) {
                root.putPOJO("additional_properties", sharedAdditionalProperties);
            }


            ArrayNode rankToReward = objectMapper.createArrayNode();
            for (int i = 0; i < decisions.size(); i++) {
                var decision = decisions.get(i);
                var actionIdx = decision.getActionIndex();
                checkState(i == actionIdx);
                var score = decision.getScore();
                var probability = decision.getProbability();
                var outcome = ex.getOutcomes().get(i);
                checkState(i == outcome.getRankingDecision().getActionIndex());
                var reward = rewardFunction.applyAsDouble(outcome.getOutcome());
                var result = objectMapper.createObjectNode();
                var rank = actionIdxToRank.get(actionIdx);
                result.put("rank", rank);
                if (score != null) {
                    result.put("score", score);
                }
                if (probability != null) {
                    result.put("probability", probability);
                }
                result.put("reward", reward);
                Map<String, Object> outcomeAdditionalProperties = getAdditionalProperties(outcome.getOutcome());
                Map<String, Object> actionAdditionalProperties = getAdditionalProperties(decision.getAction());
                Map<String, Object> decisionAdditionalProperties = decision.getAdditionalProperties();
                Map<String, Object> merged = mergeAdditionalProperties(outcomeAdditionalProperties, actionAdditionalProperties, decisionAdditionalProperties);
                if (!merged.isEmpty()) {
                    result.putPOJO("additional_properties", merged);
                }
                rankToReward.add(result);
            }
            root.set("result", rankToReward);
            try {
                return objectMapper.writeValueAsString(root);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private Map<Integer, Integer> toActionIdxToRank(List<RankingDecision<ACTION>> decisions) {
        Map<Integer, Integer> ret = new HashMap<>();
        for (int i = 0; i < decisions.size(); i++) {
            ret.put(decisions.get(i).getActionIndex(), i);
        }
        return ret;
    }
}
