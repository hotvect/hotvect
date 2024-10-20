package com.hotvect.core.transform.regression;

import com.google.common.collect.Sets;
import com.hotvect.api.data.DataRecord;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.RawValue;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

//TODO Add test
public class ScoringFeatureTransformer<RECORD, FEATURE extends Enum<FEATURE> & FeatureNamespace> implements ScoringTransformer<RECORD, FEATURE> {
    private final Class<FEATURE> featureKeyClass;
    private final EnumMap<FEATURE, Function<RECORD, RawValue>> transformations;
    private final Set<FEATURE> featureKeys;
    public ScoringFeatureTransformer(Class<FEATURE> featureKeyClass, Map<FEATURE, Function<RECORD, RawValue>> transformations){
        this.featureKeyClass = featureKeyClass;
        this.transformations = new EnumMap<>(transformations);
        this.featureKeys = Sets.immutableEnumSet(transformations.keySet());
    }

    @Override
    public DataRecord<FEATURE, RawValue> apply(RECORD toTransform) {
        DataRecord<FEATURE, RawValue> ret = new DataRecord<>(this.featureKeyClass);
        for (FEATURE feature : featureKeys) {
            RawValue processed = transformations.get(feature).apply(toTransform);
            if (processed != null){
                ret.put(feature, processed);
            }
        }

        return ret;
    }
}
