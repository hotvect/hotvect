package com.eshioji.hotvect.core.transform.ranking;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.RawValue;
import com.eshioji.hotvect.core.util.ListTransform;
import com.google.common.collect.Sets;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FeatureTransformer<SHARED, ACTION, FEATURE extends Enum<FEATURE> & FeatureNamespace> implements Transformer<SHARED, ACTION, FEATURE> {
    private final Class<FEATURE> featureKeyClass;
    private final EnumMap<FEATURE, Transformation<SHARED, ACTION>> transformations;
    private final Set<FEATURE> featureKeys;

    public FeatureTransformer(Class<FEATURE> featureKeyClass, Map<FEATURE, Transformation<SHARED, ACTION>> transformations) {
        this.featureKeyClass = featureKeyClass;
        this.transformations = new EnumMap<>(transformations);
        this.featureKeys = Sets.immutableEnumSet(transformations.keySet());
    }

    @Override
    public List<DataRecord<FEATURE, RawValue>> apply(SHARED shared, List<ACTION> actions) {
        return ListTransform.map(actions, action -> {
            DataRecord<FEATURE, RawValue> ret = new DataRecord<>(this.featureKeyClass);
            for (FEATURE feature : featureKeys) {
                RawValue processed = transformations.get(feature).apply(shared, action);
                if (processed != null) {
                    ret.put(feature, processed);
                }
            }
            return ret;
        });
    }
}
