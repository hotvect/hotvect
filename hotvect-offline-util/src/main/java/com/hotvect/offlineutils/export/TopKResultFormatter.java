package com.hotvect.offlineutils.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.data.topk.TopKExample;
import com.hotvect.api.data.topk.TopKOutcome;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TopKResultFormatter <SHARED, ACTION, OUTCOME> implements BiFunction<RewardFunction<OUTCOME>, TopK<SHARED, ACTION>, Function<TopKExample<SHARED, ACTION, OUTCOME>, String>> {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Function<TopKExample<SHARED, ACTION, OUTCOME>, String> apply(RewardFunction<OUTCOME> rewardFunction, TopK<SHARED, ACTION> topK) {
        return ex -> {
            var topKResult = topK.apply(ex.getTopKRequest());

            Map<String, OUTCOME> actionIdToOutcome = ex.getOutcomes().stream()
                    .collect(Collectors.toMap(
                            x -> x.getTopKDecision().getActionId(),
                            TopKOutcome::getOutcome
                    ));

            ObjectNode root = objectMapper.createObjectNode();
            root.put("example_id", ex.getExampleId());
            Map<String, Object> sharedAdditionalProperties = new HashMap<>();
            sharedAdditionalProperties.putAll(topKResult.getAdditionalProperties());
            sharedAdditionalProperties.putAll(getAdditionalProperties(ex.getTopKRequest().getShared()));
            if (!sharedAdditionalProperties.isEmpty()) {
                root.putPOJO("additional_properties", sharedAdditionalProperties);
            }


            ArrayNode rankToReward = objectMapper.createArrayNode();
            for (int i = 0; i < topKResult.getTopKDecisions().size(); i++) {
                var topKDecision = topKResult.getTopKDecisions().get(i);
                var actionId = topKDecision.getActionId();
                var score = topKDecision.getScore();
                var probability = topKDecision.getProbability();
                var outcome = actionIdToOutcome.get(actionId);


                double reward;
                if (outcome == null){
                    reward = 0.0;
                } else {
                    reward = rewardFunction.applyAsDouble(outcome);
                }
                var result = objectMapper.createObjectNode();
                var rank = i;
                result.put("rank", rank);
                if (score != null) {
                    result.put("score", score);
                }
                if (probability != null) {
                    result.put("probability", probability);
                }
                result.put("reward", reward);
                Map<String, Object> outcomeAdditionalProperties = getAdditionalProperties(outcome);
                Map<String, Object> actionAdditionalProperties = getAdditionalProperties(topKDecision.getAction());
                Map<String, Object> decisionAdditionalProperties = topKDecision.getAdditionalProperties();
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

    @SafeVarargs
    private Map<String, Object> mergeAdditionalProperties(Map<String, Object>... properties) {
        Map<String, Object> ret = new HashMap<>();
        for (Map<String, Object> ps : properties) {
            if(ps!=null){
                ret.putAll(ps);
            }
        }
        return ret;
    }

    private final ThreadLocal<IdentityHashMap<Class<?>, Optional<Method>>> ADDITIONAL_PROPERTIES_GETTER_CACHE = ThreadLocal.withInitial(IdentityHashMap::new);

    private Map<String, Object> getAdditionalProperties(Object object) {
        if(object == null){
            return Collections.emptyMap();
        }

        try {
            Optional<Method> getter = getGetter(object);

            if (getter.isPresent()) {
                return (Map<String, Object>) getter.get().invoke(object);
            } else {
                // No additional properties
                return Collections.emptyMap();
            }
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    private Optional<Method> getGetter(Object object) {

        Optional<Method> ret = ADDITIONAL_PROPERTIES_GETTER_CACHE.get().get(object.getClass());
        if (ret != null) {
            // If we already looked and have the method cached if available, return that
            return ret;
        }

        // We haven't looked yet if the method is available
        try {
            ret = Optional.of(object.getClass().getMethod("getAdditionalProperties"));
        } catch (NoSuchMethodException e) {
            // Additional properties do not exist
            ret = Optional.empty();
        }
        ADDITIONAL_PROPERTIES_GETTER_CACHE.get().put(object.getClass(), ret);
        return ret;
    }
}
