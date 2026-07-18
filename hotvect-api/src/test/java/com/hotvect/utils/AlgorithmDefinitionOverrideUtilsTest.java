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

        assertEquals("You may not override algorithm_name", ex.getMessage());
    }

    @Test
    void applyOverrideAllowsSameProtectedFieldValues() throws Exception {
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

        JsonNode effective = AlgorithmDefinitionOverrideUtils.applyOverride(base, override);

        assertEquals(
                OBJECT_MAPPER.readTree("""
                        {
                          "algorithm_name": "algo",
                          "algorithm_version": "10.0.0",
                          "algorithm_factory_classname": "com.example.AlgoFactory",
                          "training_lag_days": 14
                        }
                        """),
                effective
        );
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
}
