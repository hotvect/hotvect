package com.hotvect.offlineutils.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.audit.AuditableRankingVectorizer;
import com.hotvect.api.audit.RawFeatureName;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.ranking.RankingExample;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hotvect.offlineutils.export.Utils.addFeatures;

@Deprecated(forRemoval = true)
public class RankingAuditEncoder<SHARED, ACTION, OUTCOME> implements RankingExampleEncoder<SHARED, ACTION, OUTCOME> {
    private static final String FEATURE_STORE_RESPONSES_KEY = "__feature_store_responses";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final ThreadLocal<Map<Integer, List<RawFeatureName>>> names;
    private final AuditableRankingVectorizer<SHARED, ACTION> vectorizer;
    private final RewardFunction<OUTCOME> rewardFunction;
    private final boolean includeFeatureStoreResponses;

    public RankingAuditEncoder(AuditableRankingVectorizer<SHARED, ACTION> vectorizer, RewardFunction<OUTCOME> rewardFunction, boolean includeFeatureStoreResponses) {
        this.vectorizer = vectorizer;
        this.rewardFunction = rewardFunction;
        this.includeFeatureStoreResponses = includeFeatureStoreResponses;
        this.names = vectorizer.enableAudit();
    }


    @Override
    public String apply(RankingExample<SHARED, ACTION, OUTCOME> toEncode) {
        List<SparseVector> vector = vectorizer.apply(toEncode.rankingRequest());
        return jsonEncode(toEncode, vector, names.get());
    }


    private String jsonEncode(RankingExample<SHARED, ACTION, OUTCOME> toEncode, List<SparseVector> vector, Map<Integer, List<RawFeatureName>> names) {
        var root = objectMapper.createObjectNode();
        root.put("example_id", toEncode.exampleId());

        if (includeFeatureStoreResponses) {
            Map<String, Object> additionalProperties = new HashMap<>();
            additionalProperties.put(
                    FEATURE_STORE_RESPONSES_KEY,
                    toEncode.request().featureStoreResponseContainer().featureStoreResponses()
            );
            root.putPOJO("additional_properties", additionalProperties);
        }

        ArrayNode results = objectMapper.createArrayNode();
        var actions = toEncode.rankingRequest().availableActions();
        var outcomes = toEncode.outcomes();
        Map<Integer, Double> actionIdxToReward = outcomes.stream().collect(Collectors.toMap(
                x -> x.rankingDecision().getActionIndex(),
                x -> rewardFunction.applyAsDouble(x.outcome())
        ));

        for (int i = 0; i < actions.size(); i++) {
            var vectorized = vector.get(i);
            var reward = actionIdxToReward.get(i);
            var result = objectMapper.createObjectNode();
            result.put("reward", reward);
            ArrayNode features = objectMapper.createArrayNode();
            result.set("features", features);

            int[] numericalIndices = vectorized.getNumericalIndices();
            double[] numericalValues = vectorized.getNumericalValues();
            addFeatures(names, numericalIndices, numericalValues, features);

            int[] categoricalIndices = vectorized.getCategoricalIndices();
            addFeatures(names, categoricalIndices, null, features);

            results.add(result);

        }

        root.set("actions", results);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("unexpected error on serializing:" + root);
        }
    }


}
