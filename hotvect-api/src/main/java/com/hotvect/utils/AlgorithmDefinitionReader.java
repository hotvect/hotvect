package com.hotvect.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmDependencyDeclaration;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.execution.InputSemantic;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.stream.Collectors.toMap;

public class AlgorithmDefinitionReader {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern EMS_SLOT_NAME = Pattern.compile("^[a-z0-9-]+$");
    private final DependencyResolution dependencyResolution;

    /** Controls whether static shared dependencies retain serving semantics or become private. */
    public enum DependencyResolution {
        ONLINE,
        OFFLINE;

        private static DependencyResolution fromInputSemantic(InputSemantic inputSemantic) {
            return inputSemantic == InputSemantic.OFFLINE ? OFFLINE : ONLINE;
        }
    }

    /** Creates a reader that preserves the strict online serving dependency contract. */
    public AlgorithmDefinitionReader() {
        this(DependencyResolution.ONLINE);
    }

    /** Creates a reader whose dependency policy follows the fixed input semantic. */
    public AlgorithmDefinitionReader(InputSemantic inputSemantic) {
        this(DependencyResolution.fromInputSemantic(inputSemantic));
    }

    public AlgorithmDefinitionReader(DependencyResolution dependencyResolution) {
        this.dependencyResolution = java.util.Objects.requireNonNull(
                dependencyResolution,
                "dependencyResolution must not be null");
    }

    public DependencyResolution dependencyResolution() {
        return dependencyResolution;
    }

    private static JsonNode ensureExtract(JsonNode root, String fieldName) {
        JsonNode field = root.get(fieldName);
        checkArgument(field != null, "You must specify:%s. Full input:%s", fieldName, root);
        return field;
    }

    private static String ensureNonBlankTextualExtract(JsonNode root, String fieldName) {
        JsonNode field = ensureExtract(root, fieldName);
        if (!field.isTextual() || field.asText().isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be a non-blank string");
        }
        return field.asText();
    }

    private static Optional<JsonNode> optionalExtract(JsonNode root, String fieldName) {
        return Optional.ofNullable(root.get(fieldName));
    }

    public AlgorithmDefinition parse(String  json) throws IOException {
        return parse(OBJECT_MAPPER.readTree(json));
    }

    /**
     * Validates one JAR-embedded definition against the committed online contract before resolving
     * its dependencies for this reader's execution mode.
     *
     * <p>Offline-only effective syntax belongs in an explicit override. It must not make an invalid
     * packaged definition appear valid.</p>
     */
    public AlgorithmDefinition parseCommitted(String json) throws IOException {
        return parseCommitted(OBJECT_MAPPER.readTree(json));
    }

    /** See {@link #parseCommitted(String)}. */
    public AlgorithmDefinition parseCommitted(JsonNode json) throws IOException {
        AlgorithmDefinition committed = dependencyResolution == DependencyResolution.ONLINE
                ? parse(json)
                : new AlgorithmDefinitionReader(DependencyResolution.ONLINE).parse(json);
        validateCommittedOverrideFragment(
                committed.rawAlgorithmDefinition(),
                "Committed algorithm definition " + committed.algorithmId().value(),
                true);
        return dependencyResolution == DependencyResolution.ONLINE ? committed : parse(json);
    }

    public AlgorithmDefinition parse(JsonNode json) throws IOException {
        String generateStateFactoryName = optionalExtract(json, "generator_factory_classname").map(JsonNode::asText).orElse(null);
        String algorithmFactoryName = optionalExtract(json, "algorithm_factory_classname").map(JsonNode::asText).orElse(null);

        // Validate: algorithm_factory_classname is required unless this is a state algorithm
        if (algorithmFactoryName == null && generateStateFactoryName == null) {
            throw new IllegalArgumentException("You must specify:algorithm_factory_classname. Full input:" + json);
        }

        AlgorithmDefinition definition = new AlgorithmDefinition(
                json,
                new AlgorithmId(
                        ensureNonBlankTextualExtract(json, "algorithm_name"),
                        ensureNonBlankTextualExtract(json, "algorithm_version")
                ),
                extractDependencyDeclarations(json, "dependencies"),
                generateStateFactoryName,
                optionalExtract(json, "decoder_factory_classname").map(JsonNode::asText).orElse(null),
                optionalExtract(json, "transformer_factory_classname").map(JsonNode::asText).orElse(null),
                optionalExtract(json, "vectorizer_factory_classname").map(JsonNode::asText).orElse(null),
                optionalExtract(json, "reward_function_factory_classname").map(JsonNode::asText).orElse(null),
                optionalExtract(json, "encoder_factory_classname").map(JsonNode::asText).orElse(null),
                algorithmFactoryName,
                Optional.ofNullable(json.get("transformer_parameters")),
                Optional.ofNullable(json.get("vectorizer_parameters")),
                Optional.ofNullable(json.get("train_decoder_parameters")),
                Optional.ofNullable(json.get("test_decoder_parameters")),
                Optional.ofNullable(json.get("algorithm_parameters")));
        return definition;
    }

    private Map<String, AlgorithmDependencyDeclaration> extractDependencyDeclarations(
            JsonNode parsed,
            String fieldName) {
        if (!parsed.hasNonNull(fieldName)) {
            return Map.of();
        }
        JsonNode dependencyNode = parsed.get(fieldName);
        TreeMap<String, AlgorithmDependencyDeclaration> declarations = new TreeMap<>();
        if (dependencyNode.isArray()) {
            ArrayNode dependencies = (ArrayNode) dependencyNode;
            for (JsonNode dependency : dependencies) {
                if (!dependency.isTextual()) {
                    throw new IllegalArgumentException(
                            "Dependency declarations in " + fieldName + " must be algorithm name strings");
                }
                AlgorithmReference reference = parseAlgorithmReference(dependency.asText());
                if (dependencyResolution == DependencyResolution.ONLINE) {
                    requireUnversionedPrivateDependency(reference);
                }
                putDeclaration(declarations, new AlgorithmDependencyDeclaration.Private(
                        reference.name(),
                        Optional.empty()));
            }
            return declarations;
        }
        if (!dependencyNode.isObject()) {
            throw new IllegalArgumentException(
                    "Dependency declarations in " + fieldName + " must be an array or object");
        }
        for (var iterator = dependencyNode.fields(); iterator.hasNext(); ) {
            var field = iterator.next();
            AlgorithmReference reference = parseAlgorithmReference(field.getKey());
            putDeclaration(declarations, parseDeclaration(reference, field.getValue()));
        }
        return declarations;
    }

    private AlgorithmDependencyDeclaration parseDeclaration(AlgorithmReference reference, JsonNode declaration) {
        String name = reference.name();
        if (!declaration.isObject()) {
            throw new IllegalArgumentException(
                    "Dependency declaration for " + name + " must be a JSON object");
        }
        JsonNode scope = declaration.get("scope");
        if (scope != null) {
            if (!scope.isTextual()) {
                throw new IllegalArgumentException(
                        "Dependency " + name + " scope must be the string shared or slot");
            }
            if ("private".equals(scope.asText())) {
                throw new IllegalArgumentException(
                        "Dependency " + name + " must not declare scope: private; private is the default");
            }
            if ("slot".equals(scope.asText())) {
                if (declaration.size() != 1) {
                    throw new IllegalArgumentException(
                            "Slot-backed dependency " + name + " must contain only scope: slot");
                }
                if (reference.version().isPresent()) {
                    throw new IllegalArgumentException(
                            "Slot-backed dependency " + name + " must not declare an algorithm version");
                }
                if (!EMS_SLOT_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException(
                            "Slot-backed dependency " + name + " must match " + EMS_SLOT_NAME.pattern());
                }
                return new AlgorithmDependencyDeclaration.Slot(name);
            }
            if (!"shared".equals(scope.asText())) {
                throw new IllegalArgumentException(
                        "Dependency " + name + " scope must be shared or slot with no other fields");
            }
            if (dependencyResolution == DependencyResolution.OFFLINE) {
                ObjectNode privateOverride = ((ObjectNode) declaration).deepCopy();
                privateOverride.remove("scope");
                return new AlgorithmDependencyDeclaration.Private(
                        name,
                        privateOverride.isEmpty() ? Optional.empty() : Optional.of(privateOverride));
            }
            if (declaration.size() != 1) {
                throw new IllegalArgumentException(
                        "Shared dependency " + name + " must contain only scope: shared");
            }
            String version = reference.version().orElseThrow(() -> new IllegalArgumentException(
                    "Shared dependency " + name + " must declare an exact algorithm version as name@version"));
            return new AlgorithmDependencyDeclaration.Shared(new AlgorithmId(name, version));
        }
        if (dependencyResolution == DependencyResolution.ONLINE) {
            requireUnversionedPrivateDependency(reference);
        }
        return new AlgorithmDependencyDeclaration.Private(
                name,
                declaration.isEmpty() ? Optional.empty() : Optional.of(declaration));
    }

    private static void requireUnversionedPrivateDependency(AlgorithmReference reference) {
        if (reference.version().isPresent()) {
            throw new IllegalArgumentException(
                    "Private dependency " + reference.name() + " must not declare an algorithm version");
        }
    }

    private static void validateCommittedOverrideFragment(
            JsonNode fragment,
            String location,
            boolean completeDefinition) {
        if (fragment.has("hyperparameter_version")) {
            throw new IllegalArgumentException(
                    location
                            + " must not contain offline-only hyperparameter_version;"
                            + " supply it through an explicit offline override");
        }
        if (!completeDefinition && (fragment.has("algorithm_name") || fragment.has("algorithm_version"))) {
            throw new IllegalArgumentException(location + " must not contain algorithm identity fields");
        }

        JsonNode dependencies = fragment.get("dependencies");
        if (dependencies == null) {
            return;
        }
        if (!dependencies.isObject()) {
            if (!completeDefinition) {
                throw new IllegalArgumentException(location + " dependencies override must be a JSON object");
            }
            return;
        }
        for (var fields = dependencies.fields(); fields.hasNext(); ) {
            var field = fields.next();
            JsonNode child = field.getValue();
            if (!child.isObject()) {
                if (!completeDefinition) {
                    throw new IllegalArgumentException(
                            location + " dependency override " + field.getKey() + " must be a JSON object");
                }
                continue;
            }
            if (completeDefinition && child.has("scope")) {
                continue;
            }
            AlgorithmReference reference = parseAlgorithmReference(field.getKey());
            requireUnversionedPrivateDependency(reference);
            if (!completeDefinition && child.has("scope")) {
                throw new IllegalArgumentException(
                        location + " dependency override " + reference.name() + " must not declare scope");
            }
            validateCommittedOverrideFragment(
                    child,
                    location + " dependency override " + reference.name(),
                    false);
        }
    }

    private static void putDeclaration(
            Map<String, AlgorithmDependencyDeclaration> declarations,
            AlgorithmDependencyDeclaration declaration) {
        if (declarations.put(declaration.name(), declaration) != null) {
            throw new IllegalArgumentException("Duplicate dependency declaration: " + declaration.name());
        }
    }

    @Deprecated
    public static final Pattern ALGORITHM_ID = Pattern.compile("^([\\w\\-]+)(?:@([\\w\\-.]+))?$");



    @Deprecated
    public static String extractAlgorithmName(String algorithmIdString) {
        return parseAlgorithmReference(algorithmIdString).name();
    }

    private static AlgorithmReference parseAlgorithmReference(String value) {
        Matcher matcher = ALGORITHM_ID.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    String.format("Specified algorithm name %s does not match pattern %s", value, ALGORITHM_ID));
        }
        return new AlgorithmReference(matcher.group(1), Optional.ofNullable(matcher.group(2)));
    }

    private record AlgorithmReference(String name, Optional<String> version) {
    }


}
