package com.hotvect.api.algodefinition;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.Optional;

/** One declarative dependency policy from an algorithm definition. */
public sealed interface AlgorithmDependencyDeclaration
        permits AlgorithmDependencyDeclaration.Private,
                AlgorithmDependencyDeclaration.Shared,
                AlgorithmDependencyDeclaration.Slot {

    /** The unversioned dependency name used by the consuming artifact. */
    String name();

    /** A dependency loaded from and owned by the declaring artifact. */
    record Private(
            String name,
            Optional<JsonNode> algorithmDefinitionOverride)
            implements AlgorithmDependencyDeclaration {

        public Private {
            name = requireNonBlank(name, "dependency name");
            algorithmDefinitionOverride = Objects.requireNonNull(
                    algorithmDefinitionOverride,
                    "algorithmDefinitionOverride must not be null")
                    .map(JsonNode::deepCopy);
        }

        /** Returns a defensive copy of the declaration override, if one was supplied. */
        @Override
        public Optional<JsonNode> algorithmDefinitionOverride() {
            return algorithmDefinitionOverride.map(JsonNode::deepCopy);
        }
    }

    /** A dependency resolved from the runtime's canonical statically shared provider. */
    record Shared(AlgorithmId algorithmId) implements AlgorithmDependencyDeclaration {
        public Shared {
            Objects.requireNonNull(algorithmId, "algorithmId must not be null");
        }

        @Override
        public String name() {
            return algorithmId.algorithmName();
        }
    }

    /** A dependency selected by the EMS slot whose name is this dependency's name. */
    record Slot(String name) implements AlgorithmDependencyDeclaration {
        public Slot {
            name = requireNonBlank(name, "dependency name");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
