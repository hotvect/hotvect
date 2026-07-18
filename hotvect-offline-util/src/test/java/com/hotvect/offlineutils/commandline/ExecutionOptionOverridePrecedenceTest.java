package com.hotvect.offlineutils.commandline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionOptionOverridePrecedenceTest {
    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    void explicitCliExecutionOptionWinsOverTaskScopedAndRootAlgorithmDefinitionDefaults() throws Exception {
        Optional<JsonNode> rawAlgorithmDefinition = Optional.of(OM.readTree("""
                {
                  "hotvect_execution_parameters": {
                    "max_threads": 11,
                    "encode": {
                      "max_threads": 17
                    }
                  }
                }
                """));

        assertEquals(5, Main.resolveExecutionIntOption(rawAlgorithmDefinition, 5, "encode", "max_threads"));
    }

    @Test
    void taskScopedExecutionOptionWinsOverRootDefaultWhenCliLeavesItUnset() throws Exception {
        Optional<JsonNode> rawAlgorithmDefinition = Optional.of(OM.readTree("""
                {
                  "hotvect_execution_parameters": {
                    "max_threads": 11,
                    "encode": {
                      "max_threads": 17
                    }
                  }
                }
                """));

        assertEquals(17, Main.resolveExecutionIntOption(rawAlgorithmDefinition, -1, "encode", "max_threads"));
    }

    @Test
    void rootExecutionOptionIsUsedWhenTaskScopedDefaultIsMissingAndCliLeavesItUnset() throws Exception {
        Optional<JsonNode> rawAlgorithmDefinition = Optional.of(OM.readTree("""
                {
                  "hotvect_execution_parameters": {
                    "max_threads": 11
                  }
                }
                """));

        assertEquals(11, Main.resolveExecutionIntOption(rawAlgorithmDefinition, -1, "encode", "max_threads"));
    }

    @Test
    void explicitCliSamplesWinOverPredictTaskScopedAlgorithmDefinitionDefault() throws Exception {
        Optional<JsonNode> rawAlgorithmDefinition = Optional.of(OM.readTree("""
                {
                  "hotvect_execution_parameters": {
                    "predict": {
                      "samples": 17
                    }
                  }
                }
                """));

        assertEquals(5, Main.resolveTaskScopedExecutionIntOption(rawAlgorithmDefinition, 5, "predict", "samples"));
    }

    @Test
    void taskScopedSamplesAreUsedWhenCliLeavesThemUnset() throws Exception {
        Optional<JsonNode> rawAlgorithmDefinition = Optional.of(OM.readTree("""
                {
                  "hotvect_execution_parameters": {
                    "predict": {
                      "samples": 17
                    }
                  }
                }
                """));

        assertEquals(17, Main.resolveTaskScopedExecutionIntOption(rawAlgorithmDefinition, -1, "predict", "samples"));
    }

    @Test
    void performanceTestSamplePoolSizeIsResolvedSeparatelyFromSamples() throws Exception {
        Optional<JsonNode> rawAlgorithmDefinition = Optional.of(OM.readTree("""
                {
                  "hotvect_execution_parameters": {
                    "performance-test": {
                      "samples": 1000000,
                      "sample_pool_size": 25000
                    }
                  }
                }
                """));

        assertEquals(1000000, Main.resolveTaskScopedExecutionIntOption(rawAlgorithmDefinition, -1, "performance-test", "samples"));
        assertEquals(25000, Main.resolveTaskScopedExecutionIntOption(rawAlgorithmDefinition, -1, "performance-test", "sample_pool_size"));
        assertEquals(5000, Main.resolveTaskScopedExecutionIntOption(rawAlgorithmDefinition, 5000, "performance-test", "sample_pool_size"));
    }
}
