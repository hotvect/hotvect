package com.hotvect.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmDependencyDeclaration;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.execution.InputSemantic;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AlgorithmDefinitionReaderTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final AlgorithmDefinitionReader reader = new AlgorithmDefinitionReader();

    @Test
    void parsesEveryDependencyPolicyIntoOneImmutableDeclarationModel() throws Exception {
        AlgorithmDefinition definition = reader.parse("""
                {
                  "algorithm_name": "root",
                  "algorithm_version": "1",
                  "algorithm_factory_classname": "example.RootFactory",
                  "dependencies": {
                    "private-child": {"algorithm_parameters": {"weight": 0.7}},
                    "shared-child@3": {"scope": "shared"},
                    "candidate-slot": {"scope": "slot"}
                  }
                }
                """);

        AlgorithmDependencyDeclaration.Private privateDependency = assertInstanceOf(
                AlgorithmDependencyDeclaration.Private.class,
                definition.dependencyDeclarations().get("private-child"));
        assertEquals(0.7, privateDependency.algorithmDefinitionOverride()
                .orElseThrow().path("algorithm_parameters").path("weight").asDouble());
        assertEquals(
                new AlgorithmDependencyDeclaration.Shared(new AlgorithmId("shared-child", "3")),
                definition.dependencyDeclarations().get("shared-child"));
        assertEquals(
                new AlgorithmDependencyDeclaration.Slot("candidate-slot"),
                definition.dependencyDeclarations().get("candidate-slot"));

        assertThrows(
                UnsupportedOperationException.class,
                () -> definition.dependencyDeclarations().put(
                        "other",
                        new AlgorithmDependencyDeclaration.Shared(new AlgorithmId("other", "1"))));

        ObjectNode returnedOverride = (ObjectNode) privateDependency.algorithmDefinitionOverride().orElseThrow();
        returnedOverride.put("mutated", true);
        assertFalse(privateDependency.algorithmDefinitionOverride().orElseThrow().path("mutated").asBoolean());
    }

    @Test
    void parsesArrayDeclarationsAsPrivateDependenciesWithoutOverrides() throws Exception {
        AlgorithmDefinition definition = reader.parse("""
                {
                  "algorithm_name": "root",
                  "algorithm_version": "1",
                  "algorithm_factory_classname": "example.RootFactory",
                  "dependencies": ["child"]
                }
                """);

        assertEquals(
                new AlgorithmDependencyDeclaration.Private(
                        "child", java.util.Optional.empty()),
                definition.dependencyDeclarations().get("child"));
    }

    @Test
    void freezesAndDefensivelyReturnsEveryJsonParameter() throws Exception {
        ObjectNode source = (ObjectNode) OBJECT_MAPPER.readTree("""
                {
                  "algorithm_name": "root",
                  "algorithm_version": "1",
                  "algorithm_factory_classname": "example.RootFactory",
                  "transformer_parameters": {"values": ["transformer"]},
                  "vectorizer_parameters": {"values": ["vectorizer"]},
                  "train_decoder_parameters": {"values": ["train"]},
                  "test_decoder_parameters": {"values": ["test"]},
                  "algorithm_parameters": {"values": ["algorithm"]}
                }
                """);
        AlgorithmDefinition definition = reader.parse(source);

        ((ArrayNode) source.path("algorithm_parameters").path("values")).add("source mutation");
        assertEquals(1, definition.algorithmParameter().orElseThrow().path("values").size());
        assertEquals(
                "algorithm",
                definition.algorithmParameter().orElseThrow().path("values").path(0).asText());

        List<Supplier<Optional<JsonNode>>> accessors = List.of(
                definition::transformerParameter,
                definition::vectorizerParameter,
                definition::trainDecoderParameter,
                definition::testDecoderParameter,
                definition::algorithmParameter);
        for (var accessor : accessors) {
            ObjectNode returned = (ObjectNode) accessor.get().orElseThrow();
            returned.put("mutated", true);
            assertFalse(accessor.get().orElseThrow().path("mutated").asBoolean());
        }
    }

    @Test
    void rejectsNonTextualScope() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> reader.parse("""
                        {
                          "algorithm_name": "root",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "example.RootFactory",
                          "dependencies": {"child": {"scope": true}}
                        }
                        """));

        assertEquals("Dependency child scope must be the string shared or slot", error.getMessage());
    }

    @Test
    void rejectsRedundantPrivateAndMalformedSlotOrSharedDeclarations() {
        IllegalArgumentException privateScope = assertThrows(
                IllegalArgumentException.class,
                () -> reader.parse("""
                        {
                          "algorithm_name": "root",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "example.RootFactory",
                          "dependencies": {"child": {"scope": "private"}}
                        }
                        """));
        assertEquals(
                "Dependency child must not declare scope: private; private is the default",
                privateScope.getMessage());

        IllegalArgumentException slotShape = assertThrows(
                IllegalArgumentException.class,
                () -> reader.parse("""
                        {
                          "algorithm_name": "root",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "example.RootFactory",
                          "dependencies": {"child": {"scope": "slot", "extra": true}}
                        }
                        """));
        assertEquals(
                "Slot-backed dependency child must contain only scope: slot",
                slotShape.getMessage());

        IllegalArgumentException sharedShape = assertThrows(
                IllegalArgumentException.class,
                () -> reader.parse("""
                        {
                          "algorithm_name": "root",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "example.RootFactory",
                          "dependencies": {"child": {"scope": "shared", "extra": true}}
                        }
                        """));
        assertEquals("Shared dependency child must contain only scope: shared", sharedShape.getMessage());
    }

    @Test
    void requiresSharedVersionsAndRejectsVersionsOnPrivateOrSlotDependencies() {
        IllegalArgumentException unversionedShared = assertThrows(
                IllegalArgumentException.class,
                () -> reader.parse("""
                        {
                          "algorithm_name": "root",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "example.RootFactory",
                          "dependencies": {"child": {"scope": "shared"}}
                        }
                        """));
        assertEquals(
                "Shared dependency child must declare an exact algorithm version as name@version",
                unversionedShared.getMessage());

        IllegalArgumentException versionedPrivate = assertThrows(
                IllegalArgumentException.class,
                () -> reader.parse("""
                        {
                          "algorithm_name": "root",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "example.RootFactory",
                          "dependencies": ["child@2"]
                        }
                        """));
        assertEquals(
                "Private dependency child must not declare an algorithm version",
                versionedPrivate.getMessage());

        IllegalArgumentException versionedPrivateOverride = assertThrows(
                IllegalArgumentException.class,
                () -> reader.parse("""
                        {
                          "algorithm_name": "root",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "example.RootFactory",
                          "dependencies": {"child@2": {}}
                        }
                        """));
        assertEquals(
                "Private dependency child must not declare an algorithm version",
                versionedPrivateOverride.getMessage());

        IllegalArgumentException versionedSlot = assertThrows(
                IllegalArgumentException.class,
                () -> reader.parse("""
                        {
                          "algorithm_name": "root",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "example.RootFactory",
                          "dependencies": {"child@2": {"scope": "slot"}}
                        }
                        """));
        assertEquals(
                "Slot-backed dependency child must not declare an algorithm version",
                versionedSlot.getMessage());
    }

    @Test
    void offlineTreatsSharedDeclarationsAsPrivateAndIgnoresDependencyVersions() throws Exception {
        AlgorithmDefinitionReader offlineReader = new AlgorithmDefinitionReader(
                InputSemantic.OFFLINE);

        AlgorithmDefinition definition = offlineReader.parse("""
                {
                  "algorithm_name": "root",
                  "algorithm_version": "1",
                  "algorithm_factory_classname": "example.RootFactory",
                  "dependencies": {
                    "shared-without-version": {
                      "scope": "shared",
                      "algorithm_parameters": {"threshold": 0.7}
                    },
                    "shared-with-other-version@999": {"scope": "shared"},
                    "private-with-version@3": {}
                  }
                }
                """);

        AlgorithmDependencyDeclaration.Private sharedWithOverride = assertInstanceOf(
                AlgorithmDependencyDeclaration.Private.class,
                definition.dependencyDeclarations().get("shared-without-version"));
        assertEquals(
                0.7,
                sharedWithOverride.algorithmDefinitionOverride()
                        .orElseThrow()
                        .path("algorithm_parameters")
                        .path("threshold")
                        .asDouble());
        assertFalse(sharedWithOverride.algorithmDefinitionOverride().orElseThrow().has("scope"));
        assertEquals(
                new AlgorithmDependencyDeclaration.Private("shared-with-other-version", Optional.empty()),
                definition.dependencyDeclarations().get("shared-with-other-version"));
        assertEquals(
                new AlgorithmDependencyDeclaration.Private("private-with-version", Optional.empty()),
                definition.dependencyDeclarations().get("private-with-version"));
    }

    @Test
    void committedDefinitionsUseStrictOnlineSyntaxBeforeOfflineResolution() throws Exception {
        AlgorithmDefinitionReader offlineReader = new AlgorithmDefinitionReader(
                InputSemantic.OFFLINE);

        AlgorithmDefinition definition = offlineReader.parseCommitted("""
                {
                  "algorithm_name": "root",
                  "algorithm_version": "1",
                  "algorithm_factory_classname": "example.RootFactory",
                  "dependencies": {"shared-child@2": {"scope": "shared"}}
                }
                """);
        assertEquals(
                new AlgorithmDependencyDeclaration.Private("shared-child", Optional.empty()),
                definition.dependencyDeclarations().get("shared-child"));

        IllegalArgumentException versionedPrivate = assertThrows(
                IllegalArgumentException.class,
                () -> offlineReader.parseCommitted("""
                        {
                          "algorithm_name": "root",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "example.RootFactory",
                          "dependencies": {"private-child@2": {}}
                        }
                        """));
        assertEquals(
                "Private dependency private-child must not declare an algorithm version",
                versionedPrivate.getMessage());

        IllegalArgumentException sharedOverride = assertThrows(
                IllegalArgumentException.class,
                () -> offlineReader.parseCommitted("""
                        {
                          "algorithm_name": "root",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "example.RootFactory",
                          "dependencies": {
                            "shared-child@2": {
                              "scope": "shared",
                              "algorithm_parameters": {"threshold": 0.7}
                            }
                          }
                        }
                        """));
        assertEquals(
                "Shared dependency shared-child must contain only scope: shared",
                sharedOverride.getMessage());
    }

    @Test
    void committedDefinitionsRejectOfflineOnlyOverrideFields() {
        AlgorithmDefinitionReader offlineReader = new AlgorithmDefinitionReader(
                InputSemantic.OFFLINE);

        IllegalArgumentException hyperparameter = assertThrows(
                IllegalArgumentException.class,
                () -> offlineReader.parseCommitted("""
                        {
                          "algorithm_name": "root",
                          "algorithm_version": "1",
                          "hyperparameter_version": "candidate-a",
                          "algorithm_factory_classname": "example.RootFactory"
                        }
                        """));
        assertEquals(
                "Committed algorithm definition root@1 must not contain offline-only hyperparameter_version;"
                        + " supply it through an explicit offline override",
                hyperparameter.getMessage());

        IllegalArgumentException nestedVersion = assertThrows(
                IllegalArgumentException.class,
                () -> offlineReader.parseCommitted("""
                        {
                          "algorithm_name": "root",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "example.RootFactory",
                          "dependencies": {
                            "child": {
                              "dependencies": {"grandchild@2": {}}
                            }
                          }
                        }
                        """));
        assertEquals(
                "Private dependency grandchild must not declare an algorithm version",
                nestedVersion.getMessage());
    }

    @Test
    void requiresSlotDependenciesToUseEmsSlotNames() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> reader.parse("""
                        {
                          "algorithm_name": "root",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "example.RootFactory",
                          "dependencies": {"candidate_slot": {"scope": "slot"}}
                        }
                        """));

        assertEquals(
                "Slot-backed dependency candidate_slot must match ^[a-z0-9-]+$",
                error.getMessage());
    }

    @Test
    void rejectsNonTextualPublishedIdentityFields() {
        IllegalArgumentException algorithmName = assertThrows(
                IllegalArgumentException.class,
                () -> reader.parse("""
                        {
                          "algorithm_name": 1,
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "example.RootFactory"
                        }
                        """));
        assertEquals("algorithm_name must be a non-blank string", algorithmName.getMessage());

        IllegalArgumentException algorithmVersion = assertThrows(
                IllegalArgumentException.class,
                () -> reader.parse("""
                        {
                          "algorithm_name": "root",
                          "algorithm_version": 1,
                          "algorithm_factory_classname": "example.RootFactory"
                        }
                        """));
        assertEquals("algorithm_version must be a non-blank string", algorithmVersion.getMessage());
    }
}
