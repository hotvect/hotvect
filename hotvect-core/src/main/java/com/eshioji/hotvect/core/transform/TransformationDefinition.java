package com.eshioji.hotvect.core.transform;

import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.featurestate.FeatureState;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

public interface TransformationDefinition<K extends Enum<K> & FeatureNamespace, R> extends Function<Map<String, FeatureState>, EnumMap<K, Transformation<R>>>{
    @Override
    EnumMap<K, Transformation<R>> apply(Map<String, FeatureState> featureStates);
}
