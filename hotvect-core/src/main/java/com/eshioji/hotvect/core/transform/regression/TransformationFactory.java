package com.eshioji.hotvect.core.transform.regression;

import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.featurestate.FeatureState;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

public interface TransformationFactory<FEATURE extends Enum<FEATURE> & FeatureNamespace, RECORD> extends Function<Map<String, FeatureState>, EnumMap<FEATURE, Transformation<RECORD>>>{
    @Override
    EnumMap<FEATURE, Transformation<RECORD>> apply(Map<String, FeatureState> featureStates);
}
