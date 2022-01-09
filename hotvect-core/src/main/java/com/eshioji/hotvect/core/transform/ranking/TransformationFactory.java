package com.eshioji.hotvect.core.transform.ranking;

import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.featurestate.FeatureState;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

public interface TransformationFactory<SHARED, ACTION,  FEATURE extends Enum<FEATURE> & FeatureNamespace> extends Function<Map<String, FeatureState>, EnumMap<FEATURE, Transformation<SHARED, ACTION>>>{
    @Override
    EnumMap<FEATURE, Transformation<SHARED, ACTION>> apply(Map<String, FeatureState> featureStates);
}
