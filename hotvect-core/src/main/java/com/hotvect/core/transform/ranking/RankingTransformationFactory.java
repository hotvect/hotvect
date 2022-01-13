package com.hotvect.core.transform.ranking;

import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.featurestate.FeatureState;

import java.util.EnumMap;
import java.util.Map;

public interface RankingTransformationFactory<SHARED, ACTION,  FEATURE extends Enum<FEATURE> & FeatureNamespace> {
    EnumMap<FEATURE, InteractionTransfromation<SHARED, ACTION>> interactionTransformations(Map<String, FeatureState> featureStates);
    EnumMap<FEATURE, SharedTransformation<SHARED>> sharedTransformations(Map<String, FeatureState> featureStates);
    EnumMap<FEATURE, ActionTransformation<ACTION>> actionTransformation(Map<String, FeatureState> featureStates);
}