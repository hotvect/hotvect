package com.eshioji.hotvect.core.vectorization.regression;

import com.eshioji.hotvect.api.algodefinition.regression.VectorizerFactory;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.RawValue;
import com.eshioji.hotvect.api.featurestate.FeatureState;
import com.eshioji.hotvect.api.vectorization.regression.Vectorizer;
import com.eshioji.hotvect.core.combine.FeatureDefinition;
import com.eshioji.hotvect.core.combine.InteractionCombiner;
import com.eshioji.hotvect.core.featurestate.FeatureStateLoader;
import com.eshioji.hotvect.core.hash.AuditableHasher;
import com.eshioji.hotvect.core.transform.*;
import com.eshioji.hotvect.core.transform.regression.FeatureTransformer;
import com.eshioji.hotvect.core.transform.regression.Transformation;
import com.eshioji.hotvect.core.transform.regression.TransformationFactory;
import com.eshioji.hotvect.core.transform.regression.Transformer;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Sets;

import java.io.InputStream;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;

public class ParameterizedVectorizerFactory<FEATURE extends Enum<FEATURE> & FeatureNamespace, RECORD> implements VectorizerFactory<RECORD> {
    private final Class<FEATURE> featureKeyClass;
    private final TransformationFactory<FEATURE, RECORD> transformationFactory;

    public ParameterizedVectorizerFactory(Class<FEATURE> clazz, TransformationFactory<FEATURE, RECORD> transformationFactory) {
        this.featureKeyClass = clazz;
        this.transformationFactory = transformationFactory;
    }


    private Transformer<RECORD, FEATURE> buildTransformer(Set<FeatureDefinition<FEATURE>> featureDefinitions, Map<String, FeatureState> featureStates) {
        EnumMap<FEATURE, Transformation<RECORD>> allTransformations = this.transformationFactory.apply(featureStates);

        Set<FEATURE> requiredFeatures = featureDefinitions.stream()
                .flatMap(x -> Arrays.stream(x.getComponents())).collect(Collectors.toSet());

        checkState(allTransformations.keySet().containsAll(requiredFeatures),
                "Missing transformation definition! Missing:%s",
                Sets.difference(requiredFeatures, allTransformations.keySet()));

        EnumMap<FEATURE, Function<RECORD, RawValue>> requiredTransformations = new EnumMap<>(this.featureKeyClass);
        for (FEATURE feature : requiredFeatures) {
            requiredTransformations.put(feature, allTransformations.get(feature));
        }

        return new FeatureTransformer<>(this.featureKeyClass, requiredTransformations);

    }

    @Override
    public Vectorizer<RECORD> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters) {
        int hashbits = hyperparameters.map(x -> x.get("hash_bits"))
                .orElseThrow(() -> new RuntimeException("hash_bits not present")).asInt();

        FeatureStateLoader<RECORD> featureStateLoader = new FeatureStateLoader<>(featureKeyClass.getClassLoader());
        Map<String, FeatureState> featurestates = featureStateLoader.apply(hyperparameters, parameters);

        FeatureDefinitionExtractor<FEATURE> featureDefinitionExtractor = new FeatureDefinitionExtractor<>(this.featureKeyClass);
        Set<FeatureDefinition<FEATURE>> featureDefinitions = featureDefinitionExtractor.apply(hyperparameters.get());


        Transformer<RECORD, FEATURE> transformer = buildTransformer(featureDefinitions, featurestates);

        AuditableHasher<FEATURE> hasher = new AuditableHasher<>(featureKeyClass);

        return new DefaultVectorizer<>(
                transformer,
                hasher,
                new InteractionCombiner<>(hashbits, featureDefinitions)
        );

    }
}