package com.hotvect.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AlgorithmDefinitionOverrideUtilsTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void applyOverridePreservesSiblingDependenciesFromArrayBase() throws Exception {
        JsonNode base = OBJECT_MAPPER.readTree("""
                {
                  "algorithm_name": "parent-algo",
                  "algorithm_version": "10.0.0",
                  "algorithm_factory_classname": "com.example.ParentFactory",
                  "dependencies": ["child-a", "child-b"]
                }
                """);
        JsonNode override = OBJECT_MAPPER.readTree("""
                {
                  "dependencies": {
                    "child-a": {
                      "number_of_training_days": 2
                    }
                  }
                }
                """);

        JsonNode effective = AlgorithmDefinitionOverrideUtils.applyOverride(base, override);

        assertEquals(
                OBJECT_MAPPER.readTree("""
                        {
                          "algorithm_name": "parent-algo",
                          "algorithm_version": "10.0.0",
                          "algorithm_factory_classname": "com.example.ParentFactory",
                          "dependencies": {
                            "child-a": {
                              "number_of_training_days": 2
                            },
                            "child-b": {}
                          }
                        }
                        """),
                effective
        );
        assertEquals(
                OBJECT_MAPPER.readTree("""
                        {
                          "algorithm_name": "parent-algo",
                          "algorithm_version": "10.0.0",
                          "algorithm_factory_classname": "com.example.ParentFactory",
                          "dependencies": ["child-a", "child-b"]
                        }
                        """),
                base
        );
    }

    @Test
    void applyOverrideRejectsUnknownDependencyName() throws Exception {
        JsonNode base = OBJECT_MAPPER.readTree("""
                {
                  "algorithm_name": "parent-algo",
                  "algorithm_version": "10.0.0",
                  "algorithm_factory_classname": "com.example.ParentFactory",
                  "dependencies": ["child-a"]
                }
                """);
        JsonNode override = OBJECT_MAPPER.readTree("""
                {
                  "dependencies": {
                    "child-b": {
                      "number_of_training_days": 2
                    }
                  }
                }
                """);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmDefinitionOverrideUtils.applyOverride(base, override)
        );

        assertEquals("Override references unknown dependency: child-b", ex.getMessage());
    }

    @Test
    void applyOverrideDeletesLeafFieldsOnNull() throws Exception {
        JsonNode base = OBJECT_MAPPER.readTree("""
                {
                  "algorithm_name": "algo",
                  "algorithm_version": "10.0.0",
                  "algorithm_factory_classname": "com.example.AlgoFactory",
                  "test_data_prefix": "test-prefix",
                  "hotvect_execution_parameters": {
                    "predict": {
                      "enabled": true,
                      "samples": 50
                    }
                  }
                }
                """);
        JsonNode override = OBJECT_MAPPER.readTree("""
                {
                  "test_data_prefix": null,
                  "hotvect_execution_parameters": {
                    "predict": {
                      "samples": null
                    }
                  }
                }
                """);

        JsonNode effective = AlgorithmDefinitionOverrideUtils.applyOverride(base, override);

        assertEquals(
                OBJECT_MAPPER.readTree("""
                        {
                          "algorithm_name": "algo",
                          "algorithm_version": "10.0.0",
                          "algorithm_factory_classname": "com.example.AlgoFactory",
                          "hotvect_execution_parameters": {
                            "predict": {
                              "enabled": true
                            }
                          }
                        }
                        """),
                effective
        );
    }

    @Test
    void applyOverrideAllowsLeafTypeReplacement() throws Exception {
        JsonNode base = OBJECT_MAPPER.readTree("""
                {
                  "algorithm_name": "algo",
                  "algorithm_version": "10.0.0",
                  "algorithm_factory_classname": "com.example.AlgoFactory",
                  "training_lag_days": 7
                }
                """);
        JsonNode override = OBJECT_MAPPER.readTree("""
                {
                  "training_lag_days": "7"
                }
                """);

        JsonNode effective = AlgorithmDefinitionOverrideUtils.applyOverride(base, override);

        assertEquals("7", effective.get("training_lag_days").asText());
    }

    @Test
    void applyOverrideRejectsProtectedFields() throws Exception {
        JsonNode base = OBJECT_MAPPER.readTree("""
                {
                  "algorithm_name": "algo",
                  "algorithm_version": "10.0.0",
                  "algorithm_factory_classname": "com.example.AlgoFactory"
                }
                """);
        JsonNode override = OBJECT_MAPPER.readTree("""
                {
                  "algorithm_name": "other"
                }
                """);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmDefinitionOverrideUtils.applyOverride(base, override)
        );

        assertEquals(
                "Algorithm definition override must not contain identity field: algorithm_name",
                ex.getMessage());
    }

    @Test
    void applyOverrideRejectsSameIdentityFieldValues() throws Exception {
        JsonNode base = OBJECT_MAPPER.readTree("""
                {
                  "algorithm_name": "algo",
                  "algorithm_version": "10.0.0",
                  "algorithm_factory_classname": "com.example.AlgoFactory",
                  "training_lag_days": 7
                }
                """);
        JsonNode override = OBJECT_MAPPER.readTree("""
                {
                  "algorithm_name": "algo",
                  "algorithm_version": "10.0.0",
                  "training_lag_days": 14
                }
                """);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmDefinitionOverrideUtils.applyOverride(base, override));

        assertEquals(
                "Algorithm definition override must not contain identity field: algorithm_name",
                ex.getMessage());
    }

    @Test
    void mergeOverrideFragmentsAllowsGrandchildPatchWithoutBaseDefinition() throws Exception {
        JsonNode base = OBJECT_MAPPER.readTree("""
                {
                  "dependencies": {
                    "grandchild-a": {
                      "number_of_training_days": 1
                    }
                  }
                }
                """);
        JsonNode override = OBJECT_MAPPER.readTree("""
                {
                  "dependencies": {
                    "grandchild-b": {
                      "training_lag_days": 2
                    }
                  }
                }
                """);

        JsonNode merged = AlgorithmDefinitionOverrideUtils.mergeOverrideFragments(base, override);

        assertEquals(
                OBJECT_MAPPER.readTree("""
                        {
                          "dependencies": {
                            "grandchild-a": {
                              "number_of_training_days": 1
                            },
                            "grandchild-b": {
                              "training_lag_days": 2
                            }
                          }
                        }
                        """),
                merged
        );
    }

    @Test
    void applyOverridePreservesDependencyPoliciesAndVersionedKeys() throws Exception {
        JsonNode base = OBJECT_MAPPER.readTree("""
                {
                  "algorithm_name": "root",
                  "algorithm_version": "1",
                  "algorithm_factory_classname": "example.RootFactory",
                  "dependencies": {
                    "candidate-slot": {"scope": "slot"},
                    "shared-child@2": {"scope": "shared"}
                  }
                }
                """);
        JsonNode override = OBJECT_MAPPER.readTree("""
                {
                  "dependencies": {
                    "candidate-slot": {},
                    "shared-child": {}
                  }
                }
                """);

        JsonNode effective = AlgorithmDefinitionOverrideUtils.applyOverride(base, override);

        assertEquals(
                OBJECT_MAPPER.readTree("""
                        {
                          "candidate-slot": {"scope": "slot"},
                          "shared-child@2": {"scope": "shared"}
                        }
                        """),
                effective.path("dependencies"));
    }

    @Test
    void applyOverrideRejectsVersionQualifiedDependencyKey() throws Exception {
        JsonNode base = OBJECT_MAPPER.readTree("""
                {
                  "algorithm_name": "root",
                  "algorithm_version": "1",
                  "algorithm_factory_classname": "example.RootFactory",
                  "dependencies": {"shared-child@2": {"scope": "shared"}}
                }
                """);
        JsonNode override = OBJECT_MAPPER.readTree("""
                {"dependencies": {"shared-child@999": {}}}
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmDefinitionOverrideUtils.applyOverride(base, override));

        assertEquals(
                "Dependency override key shared-child@999 must be an unversioned dependency name",
                error.getMessage());
    }

    @Test
    void mergeOverrideFragmentsRejectsVersionQualifiedDependencyKey() throws Exception {
        JsonNode fragment = OBJECT_MAPPER.readTree("""
                {"dependencies": {"shared-child@2": {}}}
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmDefinitionOverrideUtils.mergeOverrideFragments(
                        fragment,
                        OBJECT_MAPPER.createObjectNode()));

        assertEquals(
                "Dependency override key shared-child@2 must be an unversioned dependency name",
                error.getMessage());
    }

    @Test
    void applyOverrideRejectsDependencyScope() throws Exception {
        JsonNode base = OBJECT_MAPPER.readTree("""
                {
                  "algorithm_name": "root",
                  "algorithm_version": "1",
                  "algorithm_factory_classname": "example.RootFactory",
                  "dependencies": {"candidate-slot": {"scope": "slot"}}
                }
                """);
        JsonNode override = OBJECT_MAPPER.readTree("""
                {"dependencies": {"candidate-slot": {"scope": null}}}
                """);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmDefinitionOverrideUtils.applyOverride(base, override));

        assertEquals("Override for dependency candidate-slot must not declare scope", error.getMessage());
    }

    @Test
    void applyOverrideRejectsNonEmptyOverridesForScopedDependencies() throws Exception {
        JsonNode base = OBJECT_MAPPER.readTree("""
                {
                  "algorithm_name": "root",
                  "algorithm_version": "1",
                  "algorithm_factory_classname": "example.RootFactory",
                  "dependencies": {
                    "candidate-slot": {"scope": "slot"},
                    "shared-child@2": {"scope": "shared"}
                  }
                }
                """);

        IllegalArgumentException slotError = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmDefinitionOverrideUtils.applyOverride(base, OBJECT_MAPPER.readTree("""
                        {"dependencies":{"candidate-slot":{"algorithm_parameters":{"threshold":0.7}}}}
                        """)));
        assertEquals("Override for scoped dependency candidate-slot must be empty", slotError.getMessage());

        IllegalArgumentException sharedError = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmDefinitionOverrideUtils.applyOverride(base, OBJECT_MAPPER.readTree("""
                        {"dependencies":{"shared-child":{"algorithm_parameters":{"threshold":0.7}}}}
                        """)));
        assertEquals("Override for scoped dependency shared-child must be empty", sharedError.getMessage());
    }

    @Test
    void mergeOverrideFragmentsRejectsDependencyScopeAlreadyPresentInBaseFragment() throws Exception {
        JsonNode base = OBJECT_MAPPER.readTree("""
                {"dependencies":{"candidate-slot":{"scope":"slot"}}}
                """);
        JsonNode extra = OBJECT_MAPPER.readTree("{}");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmDefinitionOverrideUtils.mergeOverrideFragments(base, extra));

        assertEquals("Override for dependency candidate-slot must not declare scope", error.getMessage());
    }

    @Test
    void offlineOverrideConvertsSharedDependenciesToPrivateAndIgnoresVersionedKeys() throws Exception {
        JsonNode base = OBJECT_MAPPER.readTree("""
                {
                  "algorithm_name": "root",
                  "algorithm_version": "1",
                  "algorithm_factory_classname": "example.RootFactory",
                  "dependencies": {"shared-child@2": {"scope": "shared"}}
                }
                """);

        JsonNode effective = AlgorithmDefinitionOverrideUtils.applyOverride(
                base,
                OBJECT_MAPPER.readTree("""
                        {"dependencies":{"shared-child@999":{"algorithm_parameters":{"threshold":0.7}}}}
                        """),
                AlgorithmDefinitionReader.DependencyResolution.OFFLINE);

        assertEquals(
                OBJECT_MAPPER.readTree("""
                        {"shared-child":{"algorithm_parameters":{"threshold":0.7}}}
                        """),
                effective.path("dependencies"));

        JsonNode merged = AlgorithmDefinitionOverrideUtils.mergeOverrideFragments(
                OBJECT_MAPPER.readTree("""
                        {"dependencies":{"shared-child@999":{"algorithm_parameters":{"threshold":0.7}}}}
                        """),
                OBJECT_MAPPER.readTree("""
                        {"dependencies":{"shared-child":{"algorithm_parameters":{"limit":3}}}}
                        """),
                AlgorithmDefinitionReader.DependencyResolution.OFFLINE);
        assertEquals(
                OBJECT_MAPPER.readTree("""
                        {"dependencies":{"shared-child":{"algorithm_parameters":{"threshold":0.7,"limit":3}}}}
                        """),
                merged);
    }
}
