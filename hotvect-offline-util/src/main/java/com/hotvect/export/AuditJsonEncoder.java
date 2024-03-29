package com.hotvect.export;

import com.hotvect.api.codec.ExampleEncoder;
import com.hotvect.api.data.SparseVector;
import com.hotvect.api.data.raw.Example;
import com.hotvect.core.audit.AuditableVectorizer;
import com.hotvect.core.audit.RawFeatureName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Joiner;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkState;

public class AuditJsonEncoder<R> implements ExampleEncoder<R> {
    private static final ObjectMapper OM = new ObjectMapper();
    private final ThreadLocal<Map<Integer, List<RawFeatureName>>> names;
    private final AuditableVectorizer<R> vectorizer;

    public AuditJsonEncoder(AuditableVectorizer<R> vectorizer) {
        this.vectorizer = vectorizer;
        this.names = vectorizer.enableAudit();
    }

    @Override
    public String apply(Example<R> toEncode) {
        SparseVector vector = vectorizer.apply(toEncode.getRecord());
        return jsonEncode(toEncode, vector, names.get());
    }

    private static final Joiner JOIN_ON_HAT = Joiner.on("^");

    private String jsonEncode(Example<R> toEncode, SparseVector vector, Map<Integer, List<RawFeatureName>> names) {
        double target = toEncode.getTarget();
        int[] indices = vector.indices();
        double[] values = vector.values();

        ObjectNode root = OM.createObjectNode();
        root.put("target", target);

        ArrayNode features = OM.createArrayNode();
        root.set("features", features);

        for (int i = 0; i < indices.length; i++) {
            ObjectNode feature = OM.createObjectNode();
            feature.put("index", indices[i]);
            feature.put("value", values[i]);

            List<RawFeatureName> raws = names.get(indices[i]);

            String featureNamespace;
            String featureRawname;
            if(raws == null){
                // Special case - index 0 is a dummy feature
                checkState(indices[i] == 0, "No name was found for a non-dummy index");
                featureNamespace = "dummy";
                featureRawname = "dummy";
            } else {
                featureNamespace = JOIN_ON_HAT.join(raws.stream().map(r -> r.getFeatureNamespace().toString()).iterator());
                featureRawname = JOIN_ON_HAT.join(raws.stream().map(RawFeatureName::getSourceRawValue).iterator());
            }


            feature.put("feature_namespace", featureNamespace);
            feature.put("feature_name", featureRawname);

            features.add(feature);
        }

        try {
            return OM.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("unexpected error on serializing:" + root);
        }

    }

}
