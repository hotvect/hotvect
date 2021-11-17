package com.eshioji.hotvect.core.transform;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.raw.RawValue;
import com.google.common.collect.Sets;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

//TODO Add test
public class FeatureTransformer<R, OUT extends Enum<OUT> & FeatureNamespace> implements Transformer<R, OUT>{
    private final Class<OUT> featureKeyClass;
    private final EnumMap<OUT, Function<R, RawValue>> transformations;
    private final Set<OUT> featureKeys;
    public FeatureTransformer(Class<OUT> featureKeyClass, Map<OUT, Function<R, RawValue>> transformations){
        this.featureKeyClass = featureKeyClass;
        this.transformations = new EnumMap<>(transformations);
        this.featureKeys = Sets.immutableEnumSet(transformations.keySet());
    }

    @Override
    public DataRecord<OUT, RawValue> apply(R toTransform) {
        DataRecord<OUT, RawValue> ret = new DataRecord<OUT, RawValue>(this.featureKeyClass);
        for (OUT out : featureKeys) {
            RawValue processed = transformations.get(out).apply(toTransform);
            if (processed != null){
                ret.put(out, processed);
            }
        }

        return ret;
    }
}
