package com.hotvect.offlineutils.commandline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.execution.WorkloadMode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PerformanceTestTaskWorkloadModeTest {
    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    void defaultsToRealtimeWhenUnset() {
        assertEquals(WorkloadMode.REALTIME, PerformanceTestTask.resolveWorkloadMode(null));
        assertEquals(WorkloadMode.REALTIME, PerformanceTestTask.resolveWorkloadMode(""));
        assertEquals(WorkloadMode.REALTIME, PerformanceTestTask.resolveWorkloadMode("   "));
    }

    @Test
    void parsesSupportedModesCaseInsensitively() {
        assertEquals(WorkloadMode.REALTIME, PerformanceTestTask.resolveWorkloadMode("realtime"));
        assertEquals(WorkloadMode.REALTIME, PerformanceTestTask.resolveWorkloadMode("REALTIME"));
        assertEquals(WorkloadMode.BATCH, PerformanceTestTask.resolveWorkloadMode("batch"));
        assertEquals(WorkloadMode.BATCH, PerformanceTestTask.resolveWorkloadMode("BATCH"));
    }

    @Test
    void rejectsUnsupportedModes() {
        assertThrows(IllegalArgumentException.class, () -> PerformanceTestTask.resolveWorkloadMode("online"));
    }

    @Test
    void prefersExplicitCliWorkloadModeOverAlgorithmDefinition() throws Exception {
        Optional<JsonNode> rawAlgorithmDef = Optional.of(OM.readTree("""
                {
                  "hotvect_execution_parameters": {
                    "performance-test": {
                      "workload_mode": "batch"
                    }
                  }
                }
                """));

        assertEquals("realtime", Main.resolvePerformanceTestWorkloadMode(rawAlgorithmDef, "realtime"));
    }

    @Test
    void fallsBackToAlgorithmDefinitionWhenCliWorkloadModeIsUnset() throws Exception {
        Optional<JsonNode> rawAlgorithmDef = Optional.of(OM.readTree("""
                {
                  "hotvect_execution_parameters": {
                    "performance-test": {
                      "workload_mode": "batch"
                    }
                  }
                }
                """));

        assertEquals("batch", Main.resolvePerformanceTestWorkloadMode(rawAlgorithmDef, null));
        assertEquals("batch", Main.resolvePerformanceTestWorkloadMode(rawAlgorithmDef, "   "));
    }
}
