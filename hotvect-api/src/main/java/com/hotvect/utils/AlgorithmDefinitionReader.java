package com.hotvect.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.stream.Collectors.toMap;

public class AlgorithmDefinitionReader {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static JsonNode ensureExtract(JsonNode root, String fieldName) {
        JsonNode field = root.get(fieldName);
        checkArgument(field != null, "You must specify:%s. Full input:%s", fieldName, root);
        return field;
    }

    private static Optional<JsonNode> optionalExtract(JsonNode root, String fieldName) {
        return Optional.ofNullable(root.get(fieldName));
    }

    public AlgorithmDefinition parse(String  json) throws IOException {
        return parse(OBJECT_MAPPER.readTree(json));
    }

    public AlgorithmDefinition parse(JsonNode json) throws IOException {
        return new AlgorithmDefinition(
                json,
                new AlgorithmId(
                        ensureExtract(json, "algorithm_name").asText(),
                        ensureExtract(json, "algorithm_version").asText()
                ),
                // We do read the name of the dependencies and any overrides that might be defined in this algo def
                extractDependencies(json, "dependencies"),
                // But we do not fully resolve the dependency algo def themselves yet
                // Hence null is provided
                null,
                ensureExtract(json, "decoder_factory_classname").asText(),
                optionalExtract(json, "transformer_factory_classname").map(JsonNode::asText).orElse(null),
                optionalExtract(json, "vectorizer_factory_classname").map(JsonNode::asText).orElse(null),
                ensureExtract(json, "reward_function_factory_classname").asText(),
                optionalExtract(json, "encoder_factory_classname").map(JsonNode::asText).orElse(null),
                ensureExtract(json, "algorithm_factory_classname").asText(),
                Optional.ofNullable(json.get("transformer_parameters")),
                Optional.ofNullable(json.get("vectorizer_parameters")),
                Optional.ofNullable(json.get("train_decoder_parameters")),
                Optional.ofNullable(json.get("test_decoder_parameters")),
                Optional.ofNullable(json.get("algorithm_parameters")));
    }

    private Map<String, Optional<JsonNode>> extractDependencies(JsonNode parsed, String fieldName) throws IOException {
        if (parsed.hasNonNull(fieldName)) {
            // We have dependencies
            JsonNode dependencyNode = parsed.get(fieldName);
            if (dependencyNode.isArray()){
                // We have dependencies that have no algorithm definition overwrites
                ArrayNode dependencies = (ArrayNode) dependencyNode;
                return Streams.stream(dependencies.iterator()).map(dep -> extractAlgorithmName(dep.asText())).collect(toMap(
                        algoName -> algoName,
                        _algoName -> Optional.empty()
                ));
            } else {
                // We have dependencies that do have algorithm definition overwrites
                ImmutableMap.Builder<String, Optional<JsonNode>> ret = ImmutableMap.builder();
                checkArgument(dependencyNode.isObject());
                for (var iter = dependencyNode.fields(); iter.hasNext(); ) {
                    var field = iter.next();
                    String key = extractAlgorithmName(field.getKey());
                    JsonNode algoDefOverride = field.getValue();
                    ret.put(key, Optional.of(algoDefOverride));
                }
                return ret.build();
            }
        } else {
            return Collections.emptyMap();
        }

    }

    @Deprecated
    public static final Pattern ALGORITHM_ID = Pattern.compile("^([\\w\\-]+)(@[\\w\\-.]+)?$");



    @Deprecated
    public static String extractAlgorithmName(String algorithmIdString) {
        Matcher matcher = ALGORITHM_ID.matcher(algorithmIdString);
        if (matcher.find() && matcher.groupCount() >= 2) {
            return matcher.group(1);
        } else {
            throw new IllegalArgumentException(String.format("Specified algorithm name %s does not match pattern %s", algorithmIdString, ALGORITHM_ID));
        }
    }


}
