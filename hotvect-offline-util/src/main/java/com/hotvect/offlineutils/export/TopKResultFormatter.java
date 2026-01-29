package com.hotvect.offlineutils.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.data.topk.TopKDecision;
import com.hotvect.api.data.topk.TopKExample;
import com.hotvect.api.data.topk.TopKOutcome;
import com.hotvect.api.data.topk.TopKResponse;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TopKResultFormatter<SHARED, ACTION, OUTCOME> implements BiFunction<RewardFunction<OUTCOME>, TopK<SHARED, ACTION>, Function<TopKExample<SHARED, ACTION, OUTCOME>, String>> {

    protected final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Function<TopKExample<SHARED, ACTION, OUTCOME>, String> apply(RewardFunction<OUTCOME> rewardFunction, TopK<SHARED, ACTION> topK) {
        return ex -> {
            TopKResponse<ACTION> topKResult = topK.apply(ex.request());
            ObjectNode root = createResultNode(ex, topKResult, rewardFunction);
            try {
                return objectMapper.writeValueAsString(root);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        };
    }

    protected ObjectNode createResultNode(
            TopKExample<SHARED, ACTION, OUTCOME> ex,
            TopKResponse<ACTION> topKResult,
            RewardFunction<OUTCOME> rewardFunction) {

        ObjectNode root = objectMapper.createObjectNode();
        root.put("example_id", ex.exampleId());

        // Shared additional properties
        Map<String, Object> sharedAdditionalProperties = new HashMap<>();
        sharedAdditionalProperties.putAll(topKResult.additionalProperties());
        sharedAdditionalProperties.putAll(getAdditionalProperties(ex.request().shared()));
        if (!sharedAdditionalProperties.isEmpty()) {
            root.putPOJO("additional_properties", sharedAdditionalProperties);
        }

        // Extension point for subclasses
        addCustomFields(root, ex, topKResult);

        // Build result array
        ArrayNode rankToReward = objectMapper.createArrayNode();
        Map<String, OUTCOME> actionIdToOutcome = ex.outcomes().stream()
                .collect(Collectors.toMap(
                        x -> x.topKDecision().actionId(),
                        TopKOutcome::outcome
                ));

        List<TopKDecision<ACTION>> decisions = topKResult.decisions();
        for (int i = 0; i < decisions.size(); i++) {
            TopKDecision<ACTION> topKDecision = decisions.get(i);
            String actionId = topKDecision.actionId();
            Double score = topKDecision.score();
            Double probability = topKDecision.probability();
            OUTCOME outcome = actionIdToOutcome.get(actionId);

            double reward = (outcome != null) ? rewardFunction.applyAsDouble(outcome) : 0.0;

            ObjectNode result = objectMapper.createObjectNode();
            result.put("action_id", actionId);
            result.put("rank", i);
            if (score != null) {
                result.put("score", score);
            }
            if (probability != null) {
                result.put("probability", probability);
            }
            result.put("reward", reward);

            Map<String, Object> outcomeAdditionalProperties = getAdditionalProperties(outcome);
            Map<String, Object> actionAdditionalProperties = getAdditionalProperties(topKDecision.action());
            Map<String, Object> decisionAdditionalProperties = topKDecision.additionalProperties();
            Map<String, Object> merged = mergeAdditionalProperties(
                    outcomeAdditionalProperties,
                    actionAdditionalProperties,
                    decisionAdditionalProperties);
            if (!merged.isEmpty()) {
                result.putPOJO("additional_properties", merged);
            }

            rankToReward.add(result);
        }

        root.set("result", rankToReward);
        return root;
    }

    /**
     * Extension point for adding custom fields to the root node.
     * Subclasses can override this method to add additional fields.
     */
    protected void addCustomFields(
            ObjectNode root,
            TopKExample<SHARED, ACTION, OUTCOME> ex,
            TopKResponse<ACTION> topKResult) {
        // No-op in base class
    }

    @SafeVarargs
    protected final Map<String, Object> mergeAdditionalProperties(Map<String, Object>... properties) {
        Map<String, Object> ret = new HashMap<>();
        for (Map<String, Object> ps : properties) {
            if (ps != null) {
                ret.putAll(ps);
            }
        }
        return ret;
    }

    private final ThreadLocal<IdentityHashMap<Class<?>, Optional<Method>>> ADDITIONAL_PROPERTIES_GETTER_CACHE = ThreadLocal.withInitial(IdentityHashMap::new);

    protected Map<String, Object> getAdditionalProperties(Object object) {
        if (object == null) {
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
        } catch (Exception e) {
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
            ret = Optional.of(object.getClass().getMethod("additionalProperties"));
        } catch (NoSuchMethodException e) {
            // Additional properties do not exist
            ret = Optional.empty();
        }
        ADDITIONAL_PROPERTIES_GETTER_CACHE.get().put(object.getClass(), ret);
        return ret;
    }
}