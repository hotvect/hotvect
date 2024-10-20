package com.hotvect.offlineutils.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.algodefinition.ranking.RankingTransformer;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.RawValue;
import com.hotvect.api.data.common.NamespacedRecord;
import com.hotvect.api.data.ranking.RankingExample;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.Int2DoubleArrayMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleArrayMap;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class RankingTransformerAuditEncoder<SHARED, ACTION, OUTCOME>  implements RankingExampleEncoder<SHARED, ACTION, OUTCOME> {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RankingTransformer<SHARED, ACTION> transformer;
    private final RewardFunction<OUTCOME> rewardFunction;

    public RankingTransformerAuditEncoder(RankingTransformer<SHARED, ACTION> subject, RewardFunction<OUTCOME> rewardFunction) {
        this.transformer = subject;
        this.rewardFunction = rewardFunction;
    }

    @Override
    public String apply(RankingExample<SHARED, ACTION, OUTCOME> toEncode) {

        List<NamespacedRecord<FeatureNamespace, RawValue>> transformed = this.transformer.apply(toEncode.getRankingRequest());
        var root = objectMapper.createObjectNode();
        root.put("example_id", toEncode.getExampleId());

        ArrayNode results = objectMapper.createArrayNode();
        var actions = toEncode.getRankingRequest().getAvailableActions();
        var outcomes = toEncode.getOutcomes();
        Map<Integer, Double> actionIdxToReward = outcomes.stream().collect(Collectors.toMap(
                x -> x.getRankingDecision().getActionIndex(),
                x -> rewardFunction.applyAsDouble(x.getOutcome())
        ));

        for (int i = 0; i < actions.size(); i++) {
            var transformedRecord = transformed.get(i);
            var reward = actionIdxToReward.get(i);
            var result = objectMapper.createObjectNode();
            result.put("reward", reward);
            ObjectNode features = objectMapper.createObjectNode();
            result.set("features", features);

            for (FeatureNamespace usedFeature : this.transformer.getUsedFeatures()) {
                features.putPOJO(usedFeature.toString(), jsonEncode(transformedRecord.get(usedFeature)));
            }
            results.add(result);

        }

        root.set("actions", results);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("unexpected error on serializing:" + root);
        }

    }

    private Object jsonEncode(RawValue rawValue) {
        if(rawValue == null) return null;
        switch (rawValue.getValueType()){
            case SINGLE_NUMERICAL: return rawValue.getSingleNumerical();
            case SINGLE_CATEGORICAL: return rawValue.getSingleCategorical();
            case SINGLE_STRING: return rawValue.getSingleString();
            case STRINGS: return rawValue.getStrings();
            case STRINGS_TO_NUMERICALS: return new Object2DoubleArrayMap<String>(rawValue.getStrings(), rawValue.getNumericals());
            case CATEGORICALS: return rawValue.getCategoricals();
            case SPARSE_VECTOR:
            case CATEGORICALS_TO_NUMERICALS: return new Int2DoubleArrayMap(rawValue.getCategoricals(), rawValue.getNumericals());
            case DENSE_VECTOR: return new DoubleArrayList(rawValue.getNumericals());
            default:
                throw new AssertionError("Unknown raw value type " + rawValue.getValueType());
        }
    }
}
