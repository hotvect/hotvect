package com.hotvect.offlineutils.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Joiner;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.codec.ranking.RankingExampleEncoder;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.ranking.RankingExample;
import com.hotvect.core.audit.AuditableRankingVectorizer;
import com.hotvect.core.audit.RawFeatureName;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;

public class RankingAuditEncoder<SHARED, ACTION, OUTCOME> implements RankingExampleEncoder<SHARED, ACTION, OUTCOME> {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ThreadLocal<Map<Integer, List<RawFeatureName>>> names;
    private final AuditableRankingVectorizer<SHARED, ACTION> vectorizer;
    private final RewardFunction<OUTCOME> rewardFunction;

    public RankingAuditEncoder(AuditableRankingVectorizer<SHARED, ACTION> vectorizer, RewardFunction<OUTCOME> rewardFunction) {
        this.vectorizer = vectorizer;
        this.rewardFunction = rewardFunction;
        this.names = vectorizer.enableAudit();
    }


    @Override
    public String apply(RankingExample<SHARED, ACTION, OUTCOME> toEncode) {
        List<SparseVector> vector = vectorizer.apply(toEncode.getRankingRequest());
        return jsonEncode(toEncode, vector, names.get());
    }

    private final Joiner joinOnHat = Joiner.on("^");

    private String jsonEncode(RankingExample<SHARED, ACTION, OUTCOME> toEncode, List<SparseVector> vector, Map<Integer, List<RawFeatureName>> names) {
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

    private void addFeatures(Map<Integer, List<RawFeatureName>> names, int[] indices, double[] values, ArrayNode features) {
        for (int i = 0; i < indices.length; i++) {
            ObjectNode feature = objectMapper.createObjectNode();
            feature.put("index", indices[i]);
            if (values != null) {
                feature.put("value", values[i]);
            }

            List<RawFeatureName> raws = names.get(indices[i]);

            String featureNamespace;
            String featureRawname;
            if (raws == null) {
                // Special case - index 0 is a dummy feature
                checkState(indices[i] == 0, "No name was found for a non-dummy index");
                featureNamespace = "dummy";
                featureRawname = "dummy";
            } else {
                featureNamespace = joinOnHat.join(raws.stream().map(r -> r.getFeatureNamespace().toString()).iterator());
                featureRawname = joinOnHat.join(raws.stream().map(RawFeatureName::getSourceRawValue).iterator());
            }


            feature.put("feature_namespace", featureNamespace);
            feature.put("feature_name", featureRawname);

            features.add(feature);
        }
    }

}
