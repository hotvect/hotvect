package com.hotvect.core.transform.regression;

import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.state.State;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

public interface ScoringTransformationFactory<FEATURE extends Enum<FEATURE> & FeatureNamespace, RECORD> extends Function<Map<String, State>, EnumMap<FEATURE, RecordTransformation<RECORD>>>{
    @Override
    EnumMap<FEATURE, RecordTransformation<RECORD>> apply(Map<String, State> featureStates);
}
