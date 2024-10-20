package com.hotvect.offlineutils.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Joiner;
import com.hotvect.api.algodefinition.common.RewardFunction;
import com.hotvect.api.audit.AuditableScoringVectorizer;
import com.hotvect.api.audit.RawFeatureName;
import com.hotvect.api.codec.scoring.ScoringExampleEncoder;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.scoring.ScoringExample;

import java.util.List;
import java.util.Map;

import static com.hotvect.offlineutils.export.Utils.addFeatures;

public class ScoringAuditEncoder<RECORD, OUTCOME> implements ScoringExampleEncoder<RECORD, OUTCOME> {
    private static final ObjectMapper OM = new ObjectMapper();
    private final ThreadLocal<Map<Integer, List<RawFeatureName>>> names;
    private final AuditableScoringVectorizer<RECORD> vectorizer;
    private final RewardFunction<OUTCOME> rewardFunction;

    public ScoringAuditEncoder(AuditableScoringVectorizer<RECORD> vectorizer, RewardFunction<OUTCOME> rewardFunction) {
        this.vectorizer = vectorizer;
        this.rewardFunction = rewardFunction;
        this.names = vectorizer.enableAudit();
    }

    @Override
    public String apply(ScoringExample<RECORD, OUTCOME> toEncode) {
        SparseVector vector = vectorizer.apply(toEncode.getRecord());
        return jsonEncode(toEncode, vector, names.get());
    }

    private static final Joiner JOIN_ON_HAT = Joiner.on("^");

    private String jsonEncode(ScoringExample<RECORD, OUTCOME> toEncode, SparseVector vector, Map<Integer, List<RawFeatureName>> names) {
        double target = this.rewardFunction.applyAsDouble(toEncode.getOutcome());

        ObjectNode root = OM.createObjectNode();
        root.put("target", target);

        ArrayNode features = OM.createArrayNode();
        root.set("features", features);

        int[] numericalIndices = vector.getNumericalIndices();
        double[] numericalValues = vector.getNumericalValues();
        addFeatures(names, numericalIndices, numericalValues, features);

        int[] categoricalIndices = vector.getCategoricalIndices();
        addFeatures(names, categoricalIndices, null, features);

        try {
            return OM.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("unexpected error on serializing:" + root);
        }

    }

}
