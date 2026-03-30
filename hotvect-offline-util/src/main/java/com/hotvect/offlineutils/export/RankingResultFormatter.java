package com.hotvect.offlineutils.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
    private static final String FEATURE_STORE_RESPONSES_KEY = "__feature_store_responses";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final boolean includeFeatureStoreResponses;

    public RankingResultFormatter() {
        this(false);
    }

    public RankingResultFormatter(boolean includeFeatureStoreResponses) {
        this.includeFeatureStoreResponses = includeFeatureStoreResponses;
    }

    @Override
    public Function<RankingExample<SHARED, ACTION, OUTCOME>, String> apply(RewardFunction<OUTCOME> rewardFunction, Ranker<SHARED, ACTION> ranker) {
        return ex -> {
            var rankResult = ranker.rank(ex.rankingRequest());
            var decisions = new ArrayList<>(rankResult.decisions());
            Map<Integer, Integer> actionIdxToRank = toActionIdxToRank(decisions);

            // We want to keep the original order when we write the results
            decisions.sort(Comparator.comparingInt(RankingDecision::getActionIndex));

            ObjectNode root = objectMapper.createObjectNode();
            root.put("example_id", ex.exampleId());
            Map<String, Object> sharedAdditionalProperties = new HashMap<>();
            sharedAdditionalProperties.putAll(rankResult.additionalProperties());
            sharedAdditionalProperties.putAll(getAdditionalProperties(ex.rankingRequest().shared()));
            if (includeFeatureStoreResponses) {
                sharedAdditionalProperties.put(FEATURE_STORE_RESPONSES_KEY, rankResult.featureStoreResponseContainer().featureStoreResponses());
            }
            if (!sharedAdditionalProperties.isEmpty()) {
                root.putPOJO("additional_properties", sharedAdditionalProperties);
            }


            ArrayNode rankToReward = objectMapper.createArrayNode();
            for (int i = 0; i < decisions.size(); i++) {
                var decision = decisions.get(i);
                var actionIdx = decision.getActionIndex();
                checkState(i == actionIdx);
                var score = decision.score();
                var probability = decision.probability();
                var outcome = ex.outcomes().get(i);
                checkState(i == outcome.rankingDecision().getActionIndex());
                var reward = rewardFunction.applyAsDouble(outcome.outcome());
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
                Map<String, Object> outcomeAdditionalProperties = getAdditionalProperties(outcome.outcome());
                Map<String, Object> actionAdditionalProperties = getAdditionalProperties(decision.action());
                Map<String, Object> decisionAdditionalProperties = decision.additionalProperties();
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
