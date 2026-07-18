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
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

public class TopKResultFormatter<SHARED, ACTION, OUTCOME> implements BiFunction<RewardFunction<OUTCOME>, TopK<SHARED, ACTION>, Function<TopKExample<SHARED, ACTION, OUTCOME>, ByteBuffer>> {

    protected final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Function<TopKExample<SHARED, ACTION, OUTCOME>, ByteBuffer> apply(RewardFunction<OUTCOME> rewardFunction, TopK<SHARED, ACTION> topK) {
        return ex -> {
            TopKResponse<ACTION> topKResult = topK.apply(ex.request());
            ObjectNode root = createResultNode(ex, topKResult, rewardFunction);
            try {
                byte[] jsonBytes = objectMapper.writeValueAsBytes(root);
                byte[] withNewline = new byte[jsonBytes.length + 1];
                System.arraycopy(jsonBytes, 0, withNewline, 0, jsonBytes.length);
                withNewline[jsonBytes.length] = '\n';
                return ByteBuffer.wrap(withNewline);
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
        Map<String, OUTCOME> actionIdToOutcome = new HashMap<>();
        for (TopKOutcome<OUTCOME, ACTION> outcome : ex.outcomes()) {
            String actionId = outcome.topKDecision().actionId();
            if (actionIdToOutcome.containsKey(actionId)) {
                throw new IllegalArgumentException("Example contains duplicate outcome for action id: " + actionId);
            }
            actionIdToOutcome.put(actionId, outcome.outcome());
        }

        List<TopKDecision<ACTION>> decisions = topKResult.decisions();
        for (int i = 0; i < decisions.size(); i++) {
            TopKDecision<ACTION> topKDecision = decisions.get(i);
            String actionId = topKDecision.actionId();
            Double score = topKDecision.score();
            Double probability = topKDecision.probability();
            OUTCOME outcome = actionIdToOutcome.get(actionId);

            ObjectNode result = objectMapper.createObjectNode();
            result.put("action_id", actionId);
            result.put("rank", i);
            if (score != null) {
                result.put("score", score);
            }
            if (probability != null) {
                result.put("probability", probability);
            }
            if (outcome != null) {
                double reward = rewardFunction.applyAsDouble(outcome);
                result.put("reward", reward);
            }

            Map<String, Object> outcomeAdditionalProperties = getAdditionalProperties(outcome);
            Map<String, Object> actionAdditionalProperties = getAdditionalProperties(topKDecision.action());
            Map<String, Object> decisionAdditionalProperties = topKDecision.additionalProperties();

            // Extract and inline feature audit data if present
            if (decisionAdditionalProperties.containsKey("features")) {
                Map<String, Object> featureAuditEntry = (Map<String, Object>) decisionAdditionalProperties.get("features");
                ObjectNode featureAudit = objectMapper.createObjectNode();

                for (Map.Entry<String, Object> algoEntry : featureAuditEntry.entrySet()) {
                    String algorithmName = algoEntry.getKey();
                    Map<String, Object> features = (Map<String, Object>) algoEntry.getValue();

                    // Create algorithm node with features
                    ObjectNode algorithmNode = objectMapper.createObjectNode();
                    algorithmNode.putPOJO("features", features);
                    featureAudit.set(algorithmName, algorithmNode);
                }

                // Inline feature_audit into this result object
                result.set("feature_audit", featureAudit);

                // Remove from additional properties so it doesn't appear there too
                decisionAdditionalProperties = new HashMap<>(decisionAdditionalProperties);
                decisionAdditionalProperties.remove("features");
            }

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
                Map<String, Object> ret = (Map<String, Object>) getter.get().invoke(object);
                return ret != null ? ret : Collections.emptyMap();
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
            Method method;
            try {
                method = object.getClass().getMethod("additionalProperties");
            } catch (NoSuchMethodException e) {
                // Backwards-compatible getter used by many generated POJOs.
                method = object.getClass().getMethod("getAdditionalProperties");
            }
            method.setAccessible(true);
            ret = Optional.of(method);
        } catch (NoSuchMethodException e) {
            // Additional properties do not exist
            ret = Optional.empty();
        }
        ADDITIONAL_PROPERTIES_GETTER_CACHE.get().put(object.getClass(), ret);
        return ret;
    }
}
