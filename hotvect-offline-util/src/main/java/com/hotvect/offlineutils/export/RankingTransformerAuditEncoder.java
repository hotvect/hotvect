package com.hotvect.offlineutils.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.TransformedAction;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class RankingTransformerAuditEncoder<SHARED, ACTION, OUTCOME> implements RankingExampleEncoder<SHARED, ACTION, OUTCOME> {
    private static final String FEATURE_STORE_RESPONSES_KEY = "__feature_store_responses";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final RankingTransformer<SHARED, ACTION> transformer;
    private final RewardFunction<OUTCOME> rewardFunction;
    private final boolean includeFeatureStoreResponses;

    public RankingTransformerAuditEncoder(RankingTransformer<SHARED, ACTION> subject, RewardFunction<OUTCOME> rewardFunction, boolean includeFeatureStoreResponses) {
        this.transformer = subject;
        this.rewardFunction = rewardFunction;
        this.includeFeatureStoreResponses = includeFeatureStoreResponses;
    }

    @Override
    public String encodedFileExtension() {
        return ".jsonl";
    }

    @Override
    public ByteBuffer apply(RankingExample<SHARED, ACTION, OUTCOME> toEncode) {

        List<TransformedAction<ACTION>> transformed = this.transformer.transform(toEncode.request());
        var root = objectMapper.createObjectNode();
        root.put("example_id", toEncode.exampleId());

        if (includeFeatureStoreResponses) {
            Map<String, Object> additionalProperties = new HashMap<>();
            additionalProperties.put(
                    FEATURE_STORE_RESPONSES_KEY,
                    Objects.requireNonNull(toEncode.request().featureStoreResponseContainer(), "request.featureStoreResponseContainer is null")
                            .featureStoreResponses()
            );
            root.putPOJO("additional_properties", additionalProperties);
        }

        ArrayNode results = objectMapper.createArrayNode();
        var actions = toEncode.request().availableActions();
        var outcomes = toEncode.outcomes();
        Map<Integer, Double> actionIdxToReward = outcomes.stream().collect(Collectors.toMap(
                x -> x.rankingDecision().actionIndex(),
                x -> rewardFunction.applyAsDouble(x.outcome())
        ));

        for (int i = 0; i < actions.size(); i++) {
            var transformedRecord = transformed.get(i);
            var reward = actionIdxToReward.get(i);
            var result = objectMapper.createObjectNode();
            result.put("reward", reward);
            ObjectNode features = objectMapper.createObjectNode();
            result.set("features", features);

            for (Namespace usedFeature : this.transformer.getUsedFeatures()) {
                Object featureValue = transformedRecord.transformed().get(usedFeature);
                features.putPOJO(usedFeature.toString(), featureValue);
            }
            results.add(result);

        }

        root.set("actions", results);
        try {
            byte[] jsonBytes = objectMapper.writeValueAsBytes(root);
            byte[] bytesWithNewline = new byte[jsonBytes.length + 1];
            System.arraycopy(jsonBytes, 0, bytesWithNewline, 0, jsonBytes.length);
            bytesWithNewline[jsonBytes.length] = '\n';
            return ByteBuffer.wrap(bytesWithNewline);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("unexpected error on serializing:" + root, e);
        }

    }

}
