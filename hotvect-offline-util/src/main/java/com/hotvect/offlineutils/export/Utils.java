package com.hotvect.offlineutils.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Joiner;
import com.hotvect.api.audit.RawFeatureName;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkState;

public class Utils {
    private Utils(){}
    private static final ObjectMapper OM = new ObjectMapper();
    private static final Joiner joinOnHat = Joiner.on("^");

    static void addFeatures(Map<Integer, List<RawFeatureName>> names, int[] indices, double[] values, ArrayNode features) {
        for (int i = 0; i < indices.length; i++) {
            ObjectNode feature = OM.createObjectNode();
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
