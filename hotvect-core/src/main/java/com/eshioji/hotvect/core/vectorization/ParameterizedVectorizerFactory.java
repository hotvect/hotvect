package com.eshioji.hotvect.core.vectorization;

import com.eshioji.hotvect.api.VectorizerFactory;
import com.eshioji.hotvect.api.data.FeatureNamespace;
import com.eshioji.hotvect.api.data.raw.RawValue;
import com.eshioji.hotvect.core.transform.Transformation;
import com.eshioji.hotvect.core.transform.Transformer;
import com.eshioji.hotvect.api.vectorization.Vectorizer;
import com.eshioji.hotvect.core.combine.Combiner;
import com.eshioji.hotvect.core.combine.FeatureDefinition;
import com.eshioji.hotvect.core.combine.InteractionCombiner;
import com.eshioji.hotvect.core.hash.Hasher;
import com.eshioji.hotvect.core.transform.FeatureTransformer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.Sets;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

public class ParameterizedVectorizerFactory<R, K extends Enum<K> & FeatureNamespace> implements VectorizerFactory<R> {
    private final EnumMap<K, Transformation<R>> transformations;
    private final Class<K> featureKeyClass;
    private final Function<String, K> parseFun;

    public ParameterizedVectorizerFactory(EnumMap<K, Transformation<R>> transformations, Class<K> featureKeyClass) {
        this.transformations = transformations;
        this.featureKeyClass = featureKeyClass;

        // Don't know of a cleaner way
        this.parseFun = s -> {
            try {
                return (K) featureKeyClass.getDeclaredMethod("valueOf", String.class).invoke(featureKeyClass, s);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    private EnumSet<K> parse(ArrayNode node) {
        EnumSet<K> ret = EnumSet.noneOf(featureKeyClass);
        for (JsonNode jsonNode : node) {
            ret.add(parseFun.apply(jsonNode.asText()));
        }
        checkArgument(ret.size() == node.size(), "Duplicate feature key specified? : %s", node);
        return ret;
    }

    @Override
    public Vectorizer<R> apply(Optional<JsonNode> parameter) {
        ArrayNode features = parameter.map(x -> (ArrayNode) x.get("features")).orElseThrow(RuntimeException::new);
        Set<FeatureDefinition<K>> featureDefinitions = new HashSet<>();

        for (JsonNode feature : features) {
            EnumSet<K> fds = parse((ArrayNode) feature);
            featureDefinitions.add(new FeatureDefinition<>(fds));
        }

        int numberOfBits = 26;
        Combiner<K> combiner = new InteractionCombiner<>(numberOfBits, featureDefinitions);

        Hasher<K> hasher = new Hasher<>(this.featureKeyClass);


        Set<K> requiredFeatures = featureDefinitions.stream()
                .flatMap(x -> Arrays.stream(x.getComponents())).collect(Collectors.toSet());

        checkState(transformations.keySet().containsAll(requiredFeatures),
                "Missing transformation definition! Missing:%s",
                Sets.difference(requiredFeatures, transformations.keySet()));

        EnumMap<K, Function<R, RawValue>> requiredTransformations = new EnumMap<>(this.featureKeyClass);
        for (K feature : requiredFeatures) {
            requiredTransformations.put(feature, transformations.get(feature));
        }

        Transformer<R, K> transformer = new FeatureTransformer<>(this.featureKeyClass, requiredTransformations);

        return new VectorizerImpl<>(
                transformer,
                hasher,
                combiner
        );
    }
}
