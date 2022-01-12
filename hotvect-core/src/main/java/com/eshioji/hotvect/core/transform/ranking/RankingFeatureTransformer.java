package com.eshioji.hotvect.core.transform.ranking;

import com.eshioji.hotvect.api.data.DataRecord;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.RawValue;
import com.eshioji.hotvect.core.util.ListTransform;
import com.google.common.collect.Sets;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkState;

public class RankingFeatureTransformer<SHARED, ACTION, FEATURE extends Enum<FEATURE> & FeatureNamespace> implements RankingTransformer<SHARED, ACTION, FEATURE> {
    private final Class<FEATURE> featureKeyClass;
    private final EnumMap<FEATURE, SharedTransformation<SHARED>> sharedTransformations;
    private final EnumMap<FEATURE, ActionTransformation<ACTION>> actionTransformations;
    private final EnumMap<FEATURE, InteractionTransfromation<SHARED, ACTION>> interactionTransformations;

    public RankingFeatureTransformer(Class<FEATURE> featureKeyClass,
                                     Map<FEATURE, SharedTransformation<SHARED>> sharedTransformations,
                                     Map<FEATURE, ActionTransformation<ACTION>> actionTransformations,
                                     Map<FEATURE, InteractionTransfromation<SHARED, ACTION>> interactionTransformations
    ) {
        this.featureKeyClass = featureKeyClass;
        this.sharedTransformations = new EnumMap<>(sharedTransformations);
        this.actionTransformations = new EnumMap<>(actionTransformations);
        this.interactionTransformations = new EnumMap<>(interactionTransformations);

        var sharedKeys = sharedTransformations.keySet();
        var actionKeys = actionTransformations.keySet();
        var interactionKeys = interactionTransformations.keySet();

        var overlappings = Stream.of(
                Sets.intersection(sharedKeys, actionKeys),
                Sets.intersection(actionKeys, interactionKeys),
                Sets.intersection(interactionKeys, sharedKeys))
                .filter(x -> !x.isEmpty())
                .collect(Collectors.toSet());

        checkState(overlappings.isEmpty(), "Each feature can only have one transformation defined. Offending features:" + overlappings);


    }

    @Override
    public List<DataRecord<FEATURE, RawValue>> apply(SHARED shared, List<ACTION> actions) {
        DataRecord<FEATURE, RawValue> sharedTransformed = new DataRecord<>(this.featureKeyClass);
        // Shared
        for (FEATURE feature : sharedTransformations.keySet()) {
            sharedTransformed.put(feature, sharedTransformations.get(feature).apply(shared));
        }

        return ListTransform.map(actions, action -> {
            // Action
            DataRecord<FEATURE, RawValue> transformed = new DataRecord<>(sharedTransformed);
            for (FEATURE feature : actionTransformations.keySet()) {
                transformed.put(feature, actionTransformations.get(feature).apply(action));
            }
            // Interaction
            for (FEATURE feature : interactionTransformations.keySet()) {
                transformed.put(feature, interactionTransformations.get(feature).apply(shared, action));
            }
            return transformed;
        });
    }
}
