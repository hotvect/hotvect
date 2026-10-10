package com.hotvect.offlineutils.commandline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.utils.AlgorithmDefinitionReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionOptionOverridePrecedenceTest {
    private static final ObjectMapper OM = new ObjectMapper();

    private static AlgorithmDefinition definition(String executionParameters) throws Exception {
        return new AlgorithmDefinitionReader().parse("""
                {"algorithm_name":"fixture", "algorithm_version":"1",
                 "algorithm_factory_classname":"fixtures.Factory",
                 "hotvect_execution_parameters":%s}
                """.formatted(executionParameters));
    }

    @Test
    void composedPredictionHonorsCliOverridesForDifferingDefaults() throws Exception {
        List<AlgorithmDefinition> definitions = List.of(
                definition("""
                        {"predict":{"max_threads":2,"queue_length":3,"read_queue_length":4,
                         "write_queue_length":5,"batch_size":6,"samples":7,"writer_num_shards":8,"ordered":true}}
                        """),
                definition("""
                        {"predict":{"max_threads":12,"queue_length":13,"read_queue_length":14,
                         "write_queue_length":15,"batch_size":16,"samples":17,"writer_num_shards":18,"ordered":false}}
                        """));
        Options options = new Options();
        options.maxThreads = 20;
        options.queueLength = 21;
        options.readQueueLength = 22;
        options.writeQueueLength = 23;
        options.batchSize = 24;
        options.samples = 25;
        options.writerNumShards = 26;
        options.unordered = true;

        Main.applyManagedExecutionOptions(options, definitions, "predict");

        assertEquals(20, options.maxThreads);
        assertEquals(21, options.queueLength);
        assertEquals(22, options.readQueueLength);
        assertEquals(23, options.writeQueueLength);
        assertEquals(24, options.batchSize);
        assertEquals(25, options.samples);
        assertEquals(26, options.writerNumShards);
        assertTrue(options.unordered);
    }

    @ParameterizedTest
    @ValueSource(strings = {"max_threads", "queue_length", "read_queue_length", "write_queue_length",
            "batch_size", "samples", "writer_num_shards", "reader_threads"})
    void composedPredictionRejectsConflictingEffectiveSettings(String option) throws Exception {
        List<AlgorithmDefinition> definitions = List.of(
                definition("{\"predict\":{\"" + option + "\":2}}"),
                definition("{\"predict\":{\"" + option + "\":4}}"));

        var failure = assertThrows(IllegalArgumentException.class, () ->
                Main.applyManagedExecutionOptions(new Options(), definitions, "predict"));

        assertTrue(failure.getMessage().contains("predict." + option));
    }

    @Test
    void composedPredictionRejectsConflictingOrderingWithoutCliOverride() throws Exception {
        var definitions = List.of(definition("{\"predict\":{\"ordered\":true}}"), definition("{}"));
        var failure = assertThrows(IllegalArgumentException.class, () ->
                Main.applyManagedExecutionOptions(new Options(), definitions, "predict"));
        assertTrue(failure.getMessage().contains("predict.ordered"));
    }

    @Test
    void composedSettingsCompareResolvedValuesRatherThanJsonPlacement() throws Exception {
        Options options = new Options();
        Main.applyManagedExecutionOptions(options, List.of(
                definition("{\"max_threads\":4}"),
                definition("{\"max_threads\":2,\"predict\":{\"max_threads\":4}}")), "predict");
        assertEquals(4, options.maxThreads);
    }

    @ParameterizedTest
    @ValueSource(strings = {"predict", "performance-test"})
    void composedSettingsIgnoreEmptyObjectsAndSettingsTheTaskDoesNotConsume(String task) throws Exception {
        Options options = new Options();
        Main.applyManagedExecutionOptions(options, List.of(
                definition("{}"), definition("{\"" + task + "\":{}}"),
                definition("{\"" + task + "\":{\"enabled\":false,\"unused\":42}}")), task);
        assertEquals(-1, options.maxThreads);
    }

    @Test
    void composedPerformanceTestHonorsCliWorkloadAndSampleOverrides() throws Exception {
        Options options = new Options();
        options.performanceTestWorkloadMode = "batch";
        options.samples = 10;
        options.samplePoolSize = 5;
        Main.applyManagedExecutionOptions(options, List.of(
                definition("{\"performance-test\":{\"workload_mode\":\"realtime\",\"samples\":20,\"sample_pool_size\":15}}"),
                definition("{\"performance-test\":{\"workload_mode\":\"batch\",\"samples\":30,\"sample_pool_size\":25}}")),
                "performance-test");
        assertEquals("batch", options.performanceTestWorkloadMode);
        assertEquals(10, options.samples);
        assertEquals(5, options.samplePoolSize);
    }

    @Test
    void composedPerformanceTestRejectsUnresolvedWorkloadConflict() throws Exception {
        var definitions = List.of(definition("{}"),
                definition("{\"performance-test\":{\"workload_mode\":\"batch\"}}"));
        var failure = assertThrows(IllegalArgumentException.class, () ->
                Main.applyManagedExecutionOptions(new Options(), definitions, "performance-test"));
        assertTrue(failure.getMessage().contains("performance-test.workload_mode"));
    }

    @Test
    void composedPerformanceTestNormalizesTheDefaultWorkloadMode() throws Exception {
        Options options = new Options();
        Main.applyManagedExecutionOptions(options, List.of(definition("{}"),
                definition("{\"performance-test\":{\"workload_mode\":\"REALTIME\"}}")), "performance-test");
        assertEquals("realtime", options.performanceTestWorkloadMode);
    }

    @Test
    void composedPerformanceTestIgnoresUnusedFileWriterSettings() throws Exception {
        Main.applyManagedExecutionOptions(new Options(), List.of(definition("{}"),
                definition("""
                        {"performance-test":{"ordered":true,"reader_threads":5,"read_queue_length":7,
                          "write_queue_length":9,"writer_num_shards":11}}
                        """)), "performance-test");
    }

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
