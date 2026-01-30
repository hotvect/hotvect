package com.hotvect.offlineutils.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.RawValue;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.api.data.ranking.TransformedAction;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.Int2DoubleArrayMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleArrayMap;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class RankingTransformerAuditEncoder<SHARED, ACTION, OUTCOME> implements RankingExampleEncoder<SHARED, ACTION, OUTCOME> {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RankingTransformer<SHARED, ACTION> transformer;
    private final RewardFunction<OUTCOME> rewardFunction;

    public RankingTransformerAuditEncoder(RankingTransformer<SHARED, ACTION> subject, RewardFunction<OUTCOME> rewardFunction) {
        this.transformer = subject;
        this.rewardFunction = rewardFunction;
    }

    @Override
    public String apply(RankingExample<SHARED, ACTION, OUTCOME> toEncode) {

        List<TransformedAction<ACTION>> transformed = this.transformer.transform(toEncode.rankingRequest());
        var root = objectMapper.createObjectNode();
        root.put("example_id", toEncode.exampleId());

        ArrayNode results = objectMapper.createArrayNode();
        var actions = toEncode.rankingRequest().availableActions();
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
                if(featureValue instanceof RawValue rawValue){
                    featureValue = jsonEncode(rawValue);
                }
                features.putPOJO(usedFeature.toString(), featureValue);
            }
            results.add(result);

        }

        root.set("actions", results);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("unexpected error on serializing:" + root, e);
        }

    }

    // TODO remove once RawValue is gone
    @Deprecated(forRemoval = true)
    private Object jsonEncode(RawValue rawValue) {
        if(rawValue == null) return null;
        return switch (rawValue.getValueType()) {
            case SINGLE_NUMERICAL -> rawValue.getSingleNumerical();
            case SINGLE_CATEGORICAL -> rawValue.getSingleCategorical();
            case SINGLE_STRING -> rawValue.getSingleString();
            case STRINGS -> rawValue.getStrings();
            case STRINGS_TO_NUMERICALS ->
                    new Object2DoubleArrayMap<String>(rawValue.getStrings(), rawValue.getNumericals());
            case CATEGORICALS -> rawValue.getCategoricals();
            case SPARSE_VECTOR, CATEGORICALS_TO_NUMERICALS ->
                    new Int2DoubleArrayMap(rawValue.getCategoricals(), rawValue.getNumericals());
            case DENSE_VECTOR -> new DoubleArrayList(rawValue.getNumericals());
        };
    }


}
