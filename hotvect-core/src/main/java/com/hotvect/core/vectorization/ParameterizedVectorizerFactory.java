package com.hotvect.core.vectorization;

import com.hotvect.api.VectorizerFactory;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.data.raw.RawValue;
import com.hotvect.api.featurestate.FeatureState;
import com.hotvect.api.vectorization.Vectorizer;
import com.hotvect.core.combine.FeatureDefinition;
import com.hotvect.core.combine.InteractionCombiner;
import com.hotvect.core.featurestate.FeatureStateLoader;
import com.hotvect.core.hash.AuditableHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Sets;
import com.hotvect.core.transform.*;

import java.io.InputStream;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;

public class ParameterizedVectorizerFactory<K extends Enum<K> & FeatureNamespace, R> implements VectorizerFactory<R> {
    private final Class<K> featureKeyClass;
    private final TransformationDefinition<K, R> transformationDefinition;

    public ParameterizedVectorizerFactory(Class<K> clazz, TransformationDefinition<K, R> transformationDefinition) {
        this.featureKeyClass = clazz;
        this.transformationDefinition = transformationDefinition;
    }


    private Transformer<R, K> buildTransformer(Set<FeatureDefinition<K>> featureDefinitions, Map<String, FeatureState> featureStates) {
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

    @Override
    public Vectorizer<R> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters) {
        int hashbits = hyperparameters.map(x -> x.get("hash_bits"))
                .orElseThrow(() -> new RuntimeException("hash_bits not present")).asInt();

        FeatureStateLoader<R> featureStateLoader = new FeatureStateLoader<>(featureKeyClass.getClassLoader());
        Map<String, FeatureState> featurestates = featureStateLoader.apply(hyperparameters, parameters);

        FeatureDefinitionExtractor<K> featureDefinitionExtractor = new FeatureDefinitionExtractor<>(this.featureKeyClass);
        Set<FeatureDefinition<K>> featureDefinitions = featureDefinitionExtractor.apply(hyperparameters.get());


        Transformer<R, K> transformer = buildTransformer(featureDefinitions, featurestates);

        AuditableHasher<K> hasher = new AuditableHasher<>(featureKeyClass);

        return new DefaultVectorizer<>(
                transformer,
                hasher,
                new InteractionCombiner<>(hashbits, featureDefinitions)
        );

    }
}