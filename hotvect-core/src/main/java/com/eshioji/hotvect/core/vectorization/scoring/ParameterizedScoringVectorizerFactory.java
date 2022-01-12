package com.eshioji.hotvect.core.vectorization.scoring;

import com.eshioji.hotvect.api.algodefinition.scoring.ScoringVectorizerFactory;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.RawValue;
import com.eshioji.hotvect.api.featurestate.FeatureState;
import com.eshioji.hotvect.api.vectorization.ScoringVectorizer;
import com.eshioji.hotvect.core.combine.FeatureDefinition;
import com.eshioji.hotvect.core.combine.InteractionCombiner;
import com.eshioji.hotvect.core.featurestate.FeatureStateLoader;
import com.eshioji.hotvect.core.hash.AuditableHasher;
import com.eshioji.hotvect.core.transform.*;
import com.eshioji.hotvect.core.transform.regression.ScoringFeatureTransformer;
import com.eshioji.hotvect.core.transform.regression.RecordTransformation;
import com.eshioji.hotvect.core.transform.regression.ScoringTransformationFactory;
import com.eshioji.hotvect.core.transform.regression.ScoringTransformer;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Sets;

import java.io.InputStream;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;

public class ParameterizedScoringVectorizerFactory<FEATURE extends Enum<FEATURE> & FeatureNamespace, RECORD> implements ScoringVectorizerFactory<RECORD> {
    private final Class<FEATURE> featureKeyClass;
    private final ScoringTransformationFactory<FEATURE, RECORD> scoringTransformationFactory;

    public ParameterizedScoringVectorizerFactory(Class<FEATURE> clazz, ScoringTransformationFactory<FEATURE, RECORD> scoringTransformationFactory) {
        this.featureKeyClass = clazz;
        this.scoringTransformationFactory = scoringTransformationFactory;
    }


    private ScoringTransformer<RECORD, FEATURE> buildTransformer(Set<FeatureDefinition<FEATURE>> featureDefinitions, Map<String, FeatureState> featureStates) {
        EnumMap<FEATURE, RecordTransformation<RECORD>> allTransformations = this.scoringTransformationFactory.apply(featureStates);

        Set<FEATURE> requiredFeatures = featureDefinitions.stream()
                .flatMap(x -> Arrays.stream(x.getComponents())).collect(Collectors.toSet());

        checkState(allTransformations.keySet().containsAll(requiredFeatures),
                "Missing transformation definition! Missing:%s",
                Sets.difference(requiredFeatures, allTransformations.keySet()));

        EnumMap<FEATURE, Function<RECORD, RawValue>> requiredTransformations = new EnumMap<>(this.featureKeyClass);
        for (FEATURE feature : requiredFeatures) {
            requiredTransformations.put(feature, allTransformations.get(feature));
        }

        return new ScoringFeatureTransformer<>(this.featureKeyClass, requiredTransformations);

    }

    @Override
    public ScoringVectorizer<RECORD> apply(Optional<JsonNode> hyperparameters, Map<String, InputStream> parameters) {
        int hashbits = hyperparameters.map(x -> x.get("hash_bits"))
                .orElseThrow(() -> new RuntimeException("hash_bits not present")).asInt();

        FeatureStateLoader featureStateLoader = new FeatureStateLoader(featureKeyClass.getClassLoader());
        Map<String, FeatureState> featurestates = featureStateLoader.apply(hyperparameters, parameters);

        FeatureDefinitionExtractor<FEATURE> featureDefinitionExtractor = new FeatureDefinitionExtractor<>(this.featureKeyClass);
        Set<FeatureDefinition<FEATURE>> featureDefinitions = featureDefinitionExtractor.apply(hyperparameters.get());


        ScoringTransformer<RECORD, FEATURE> scoringTransformer = buildTransformer(featureDefinitions, featurestates);

        AuditableHasher<FEATURE> hasher = new AuditableHasher<>(featureKeyClass);

        return new DefaultScoringVectorizer<>(
                scoringTransformer,
                hasher,
                new InteractionCombiner<>(hashbits, featureDefinitions)
        );

    }
}