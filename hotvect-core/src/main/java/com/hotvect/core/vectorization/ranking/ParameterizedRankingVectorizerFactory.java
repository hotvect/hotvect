package com.hotvect.core.vectorization.ranking;

import com.hotvect.api.algodefinition.ranking.RankingVectorizerFactory;
import com.hotvect.api.data.FeatureNamespace;
import com.hotvect.api.featurestate.FeatureState;
import com.hotvect.api.vectorization.RankingVectorizer;
import com.hotvect.core.combine.FeatureDefinition;
import com.hotvect.core.combine.InteractionCombiner;
import com.hotvect.core.featurestate.FeatureStateLoader;
import com.hotvect.core.hash.AuditableHasher;
import com.hotvect.core.transform.FeatureDefinitionExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Sets;
import com.hotvect.core.transform.ranking.RankingFeatureTransformer;
import com.hotvect.core.transform.ranking.RankingTransformationFactory;
import com.hotvect.core.transform.ranking.RankingTransformer;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkState;

public class ParameterizedRankingVectorizerFactory<SHARED, ACTION, FEATURE extends Enum<FEATURE> & FeatureNamespace> implements RankingVectorizerFactory<SHARED, ACTION> {
    private final Class<FEATURE> featureKeyClass;
    private final RankingTransformationFactory<SHARED, ACTION, FEATURE> rankingTransformationFactory;

    public ParameterizedRankingVectorizerFactory(Class<FEATURE> clazz, RankingTransformationFactory<SHARED, ACTION, FEATURE> rankingTransformationFactory) {
        this.featureKeyClass = clazz;
        this.rankingTransformationFactory = rankingTransformationFactory;
    }


    private RankingTransformer<SHARED, ACTION, FEATURE> buildTransformer(Set<FeatureDefinition<FEATURE>> featureDefinitions, Map<String, FeatureState> featureStates) {
        //EnumMap<FEATURE, Transformation<RECORD>> allTransformations = this.transformationFactory.apply(featureStates);


        var sharedTransformations = this.rankingTransformationFactory.sharedTransformations(featureStates);
        var actionTransformations = this.rankingTransformationFactory.actionTransformation(featureStates);
        var interactionTransformations = this.rankingTransformationFactory.interactionTransformations(featureStates);

        Set<FEATURE> requiredFeatures = featureDefinitions.stream()
                .flatMap(x -> Arrays.stream(x.getComponents())).collect(Collectors.toSet());

        var allDefinedTransformationKeys = Stream.of(actionTransformations.keySet(), sharedTransformations.keySet(), interactionTransformations.keySet())
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        checkState(allDefinedTransformationKeys.containsAll(requiredFeatures),
                "Missing transformation definition! Missing:%s",
                Sets.difference(requiredFeatures, allDefinedTransformationKeys));

        trim(requiredFeatures, actionTransformations, sharedTransformations, interactionTransformations);

        return new RankingFeatureTransformer<>(featureKeyClass, sharedTransformations, actionTransformations, interactionTransformations);
    }

    @SafeVarargs
    private void trim(Set<FEATURE> requiredFeatures, Map<FEATURE, ?>... transformations) {
        Arrays.stream(transformations).forEach(t -> {
            for (FEATURE feature : t.keySet()) {
                if(!requiredFeatures.contains(feature)){
                    // This transformation is not needed
                    t.remove(feature);
                }
            }
        });
    }

    @Override
    public RankingVectorizer<SHARED, ACTION> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters) {
        int hashbits = hyperparameters.map(x -> x.get("hash_bits"))
                .orElseThrow(() -> new RuntimeException("hash_bits not present")).asInt();

        FeatureStateLoader featureStateLoader = new FeatureStateLoader(featureKeyClass.getClassLoader());
        Map<String, FeatureState> featurestates = featureStateLoader.apply(hyperparameters, parameters);

        FeatureDefinitionExtractor<FEATURE> featureDefinitionExtractor = new FeatureDefinitionExtractor<>(this.featureKeyClass);
        Set<FeatureDefinition<FEATURE>> featureDefinitions = featureDefinitionExtractor.apply(hyperparameters.get());


        RankingTransformer<SHARED, ACTION, FEATURE> rankingTransformer = buildTransformer(featureDefinitions, featurestates);

        AuditableHasher<FEATURE> hasher = new AuditableHasher<>(featureKeyClass);

        return new DefaultRankingVectorizer<>(
                rankingTransformer,
                hasher,
                new InteractionCombiner<>(hashbits, featureDefinitions)
        );

    }
}