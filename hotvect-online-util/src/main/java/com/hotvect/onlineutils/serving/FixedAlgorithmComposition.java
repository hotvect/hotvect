package com.hotvect.onlineutils.serving;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** One strict, fixed algorithm composition loaded from a local JSON document. */
final class FixedAlgorithmComposition {
    private static final Comparator<AlgorithmId> BY_ALGORITHM_ID = Comparator
            .comparing(AlgorithmId::algorithmName)
            .thenComparing(AlgorithmId::algorithmVersion);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);

    private final Path source;
    private final AlgorithmId root;
    private final Map<AlgorithmId, AlgorithmMetadata> algorithms;
    private final Map<String, AlgorithmId> slotBindings;
    private final ObjectNode canonicalDefinition;

    private FixedAlgorithmComposition(
            Path source,
            AlgorithmId root,
            Map<AlgorithmId, AlgorithmMetadata> algorithms,
            Map<String, AlgorithmId> slotBindings) {
        this.source = source;
        this.root = root;
        this.algorithms = algorithms;
        this.slotBindings = slotBindings;
        this.canonicalDefinition = canonicalDefinition(root, algorithms, slotBindings);
    }

    static FixedAlgorithmComposition read(Path source) throws IOException {
        Path resolvedSource = Objects.requireNonNull(source, "source must not be null")
                .toAbsolutePath()
                .normalize();
        CompositionDocument document;
        try (var input = Files.newInputStream(resolvedSource)) {
            document = OBJECT_MAPPER.readValue(input, CompositionDocument.class);
        }

        AlgorithmId root = parseAlgorithmId(document.root(), "root");
        Map<AlgorithmId, AlgorithmMetadata> algorithms = parseAlgorithms(document.algorithms());
        if (!algorithms.containsKey(root)) {
            throw new IllegalArgumentException("Composition root " + root + " is missing from algorithms");
        }
        Map<String, AlgorithmId> slotBindings = parseSlotBindings(document.slotBindings(), algorithms);
        return new FixedAlgorithmComposition(resolvedSource, root, algorithms, slotBindings);
    }

    Path source() {
        return source;
    }

    AlgorithmId root() {
        return root;
    }

    JsonNode canonicalDefinition() {
        return canonicalDefinition.deepCopy();
    }

    AlgorithmMetadata algorithm(AlgorithmId algorithmId) {
        AlgorithmMetadata metadata = algorithms.get(algorithmId);
        if (metadata == null) {
            throw new IllegalArgumentException("Composition does not define algorithm " + algorithmId);
        }
        return metadata;
    }

    Set<AlgorithmId> algorithmIds() {
        return algorithms.keySet();
    }

    AlgorithmId slotBinding(String slotName) {
        AlgorithmId binding = slotBindings.get(slotName);
        if (binding == null) {
            throw new IllegalArgumentException("Composition does not bind slot " + slotName);
        }
        return binding;
    }

    Set<String> slotNames() {
        return slotBindings.keySet();
    }

    private static Map<AlgorithmId, AlgorithmMetadata> parseAlgorithms(
            Map<String, ArtifactDocument> configured) {
        if (configured == null || configured.isEmpty()) {
            throw new IllegalArgumentException("Composition algorithms must not be empty");
        }
        TreeMap<AlgorithmId, AlgorithmMetadata> algorithms = new TreeMap<>(BY_ALGORITHM_ID);
        configured.forEach((configuredId, artifact) -> {
            AlgorithmId algorithmId = parseAlgorithmId(configuredId, "algorithm catalog key");
            ArtifactDocument selected = Objects.requireNonNull(
                    artifact,
                    "Composition algorithm " + algorithmId + " must be an object");
            String jarUri = requireArtifactUri(selected.jarUri(), "JAR URI for " + algorithmId);
            String parameterUri = null;
            if (selected.parameter() != null) {
                parameterUri = requireArtifactUri(
                        selected.parameter().uri(),
                        "Parameter URI for " + algorithmId);
            }
            AlgorithmMetadata previous = algorithms.put(
                    algorithmId,
                    new AlgorithmMetadata(
                            algorithmId.algorithmName(),
                            algorithmId.algorithmVersion(),
                            null,
                            jarUri,
                            parameterUri));
            if (previous != null) {
                throw new IllegalArgumentException("Composition defines algorithm more than once: " + algorithmId);
            }
        });
        return Collections.unmodifiableMap(algorithms);
    }

    private static Map<String, AlgorithmId> parseSlotBindings(
            Map<String, String> configured,
            Map<AlgorithmId, AlgorithmMetadata> algorithms) {
        if (configured == null) {
            throw new IllegalArgumentException("Composition slot_bindings must be an object");
        }
        TreeMap<String, AlgorithmId> bindings = new TreeMap<>();
        configured.forEach((configuredSlot, configuredAlgorithm) -> {
            String slotName = requireNonBlank(configuredSlot, "Slot name");
            AlgorithmId algorithmId = parseAlgorithmId(
                    configuredAlgorithm,
                    "Algorithm reference in slot " + slotName);
            if (!algorithms.containsKey(algorithmId)) {
                throw new IllegalArgumentException(
                        "Composition slot " + slotName + " references unknown algorithm " + algorithmId);
            }
            bindings.put(slotName, algorithmId);
        });
        return Collections.unmodifiableMap(bindings);
    }

    private static AlgorithmId parseAlgorithmId(String value, String fieldName) {
        String resolved = requireNonBlank(value, fieldName);
        int separator = resolved.lastIndexOf('@');
        if (separator <= 0 || separator == resolved.length() - 1) {
            throw new IllegalArgumentException(
                    fieldName + " must use <algorithm-name>@<algorithm-version>: " + resolved);
        }
        return new AlgorithmId(resolved.substring(0, separator), resolved.substring(separator + 1));
    }

    private static String requireArtifactUri(String value, String fieldName) {
        String resolved = requireNonBlank(value, fieldName);
        URI uri;
        try {
            uri = URI.create(resolved);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(fieldName + " is invalid: " + resolved, error);
        }
        if (!"file".equals(uri.getScheme()) && !"s3".equals(uri.getScheme())) {
            throw new IllegalArgumentException(fieldName + " must use file:// or s3://: " + resolved);
        }
        return uri.toString();
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static ObjectNode canonicalDefinition(
            AlgorithmId root,
            Map<AlgorithmId, AlgorithmMetadata> algorithms,
            Map<String, AlgorithmId> slotBindings) {
        ObjectNode document = OBJECT_MAPPER.createObjectNode();
        document.put("root", root.value());
        ObjectNode algorithmCatalog = document.putObject("algorithms");
        algorithms.forEach((algorithmId, metadata) -> {
            ObjectNode artifact = algorithmCatalog.putObject(algorithmId.value());
            artifact.put("jar_uri", metadata.absoluteS3AlgorithmJarPath());
            if (metadata.hasParameter()) {
                ObjectNode parameter = artifact.putObject("parameter");
                parameter.put("uri", metadata.absoluteS3AlgorithmParameterPath());
            }
        });
        ObjectNode bindings = document.putObject("slot_bindings");
        slotBindings.forEach((slotName, selected) -> bindings.put(slotName, selected.value()));
        return document;
    }

    private record CompositionDocument(
            @JsonProperty(value = "root", required = true) String root,
            @JsonProperty(value = "algorithms", required = true) Map<String, ArtifactDocument> algorithms,
            @JsonProperty(value = "slot_bindings", required = true) Map<String, String> slotBindings) {
    }

    private record ArtifactDocument(
            @JsonProperty(value = "jar_uri", required = true) String jarUri,
            @JsonProperty("parameter") ParameterDocument parameter) {
    }

    private record ParameterDocument(
            @JsonProperty(value = "uri", required = true) String uri) {
    }
}
