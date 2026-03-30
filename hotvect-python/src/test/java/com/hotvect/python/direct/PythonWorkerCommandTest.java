package com.hotvect.python.direct;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PythonWorkerCommandTest {
    @Test
    void rejectsCudaVisibleDevicesInEnvironment() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PythonWorkerCommand("python3", "hotvect.direct_worker.tensorflow_worker", List.of(), Map.of("CUDA_VISIBLE_DEVICES", "0"))
        );
    }

    @Test
    void allowsOtherEnvironmentVariables() {
        assertDoesNotThrow(
                () -> new PythonWorkerCommand("python3", "hotvect.direct_worker.tensorflow_worker", List.of(), Map.of("OMP_NUM_THREADS", "4"))
        );
    }
}
