package com.eshioji.hotvect.core.transform;

import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.raw.RawValue;
import com.eshioji.hotvect.core.combine.FeatureDefinition;
import com.google.common.collect.Sets;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;

public class TransformerFactory<K extends Enum<K> & FeatureNamespace, R> implements BiFunction<Set<FeatureDefinition<K>>, Map<String, FeatureState>, Transformer<R, K>> {
    private final Class<K> featureKeyClass;
    private final TransformationDefinition<K, R> transformationDefinition;

    public TransformerFactory(Class<K> clazz, TransformationDefinition<K, R> transformationDefinition) {
        this.featureKeyClass = clazz;
        this.transformationDefinition = transformationDefinition;
    }

    @Override
    public Transformer<R, K> apply(Set<FeatureDefinition<K>> featureDefinitions, Map<String, FeatureState> featureStates) {
        EnumMap<K, Transformation<R>> allTransformations = this.transformationDefinition.apply(featureStates);

        Set<K> requiredFeatures = featureDefinitions.stream()
                .flatMap(x -> Arrays.stream(x.getComponents())).collect(Collectors.toSet());

        checkState(allTransformations.keySet().containsAll(requiredFeatures),
                "Missing transformation definition! Missing:%s",
                Sets.difference(requiredFeatures, allTransformations.keySet()));

        EnumMap<K, Function<R, RawValue>> requiredTransformations = new EnumMap<>(this.featureKeyClass);
        for (K feature : requiredFeatures) {
            requiredTransformations.put(feature, allTransformations.get(feature));
        }

        return new FeatureTransformer<>(this.featureKeyClass, requiredTransformations);

    }
}