package com.eshioji.hotvect.core.transform.regression;

import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.featurestate.FeatureState;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

public interface ScoringTransformationFactory<FEATURE extends Enum<FEATURE> & FeatureNamespace, RECORD> extends Function<Map<String, FeatureState>, EnumMap<FEATURE, RecordTransformation<RECORD>>>{
    @Override
    EnumMap<FEATURE, RecordTransformation<RECORD>> apply(Map<String, FeatureState> featureStates);
}
