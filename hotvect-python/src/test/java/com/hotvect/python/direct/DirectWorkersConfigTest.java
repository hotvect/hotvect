package com.hotvect.python.direct;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectWorkersConfigTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void clearMockNvidiaSmiOutput() {
        System.clearProperty("com.hotvect.direct_workers.nvidia_smi_output");
    }

    @Test
    void fromJson_auto_respectsEmptyParentCudaVisibleDevices() throws Exception {
        System.setProperty(
                "com.hotvect.direct_workers.nvidia_smi_output",
                "GPU 0: NVIDIA A10G (UUID: GPU-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee)\n"
        );

        JsonNode node = MAPPER.readTree("""
                {
                  "accelerator": "auto",
                  "workers_per_device": 2
                }
                """);

        DirectWorkersConfig cfg = DirectWorkersConfig.fromJson(node, "");
        assertEquals(List.of("", ""), cfg.cudaVisibleDevicesPerWorker());
    }

    @Test
    void fromJson_auto_usesAllowlistWhenParentCudaVisibleDevicesSet() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {
                  "accelerator": "auto",
                  "workers_per_device": 1
                }
                """);

        DirectWorkersConfig cfg = DirectWorkersConfig.fromJson(node, "2,3");
        assertEquals(List.of("2", "3"), cfg.cudaVisibleDevicesPerWorker());
    }

    @Test
    void fromJson_cuda_devicesIndicesReferToParentAllowlist() throws Exception {
        JsonNode node0 = MAPPER.readTree("""
                {
                  "accelerator": "cuda",
                  "devices": [0],
                  "workers_per_device": 1
                }
                """);
        DirectWorkersConfig cfg0 = DirectWorkersConfig.fromJson(node0, "2,3");
        assertEquals(List.of("2"), cfg0.cudaVisibleDevicesPerWorker());

        JsonNode node1 = MAPPER.readTree("""
                {
                  "accelerator": "cuda",
                  "devices": [1],
                  "workers_per_device": 1
                }
                """);
        DirectWorkersConfig cfg1 = DirectWorkersConfig.fromJson(node1, "2,3");
        assertEquals(List.of("3"), cfg1.cudaVisibleDevicesPerWorker());
    }

    @Test
    void fromJson_cuda_devicesIndexOutOfBoundsThrows() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {
                  "accelerator": "cuda",
                  "devices": [2],
                  "workers_per_device": 1
                }
                """);

        assertThrows(IllegalArgumentException.class, () -> DirectWorkersConfig.fromJson(node, "2,3"));
    }

    @Test
    void fromJson_auto_fallsBackToNvidiaSmiWhenNoParentEnv() throws Exception {
        System.setProperty(
                "com.hotvect.direct_workers.nvidia_smi_output",
                """
                        GPU 0: NVIDIA A10G (UUID: GPU-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee)
                        GPU 1: NVIDIA A10G (UUID: GPU-ffffffff-1111-2222-3333-444444444444)
                        """
        );

        JsonNode node = MAPPER.readTree("""
                {
                  "accelerator": "auto"
                }
                """);

        DirectWorkersConfig cfg = DirectWorkersConfig.fromJson(node, null);
        assertEquals(List.of("0", "1"), cfg.cudaVisibleDevicesPerWorker());
    }

    @Test
    void fromJson_gpu_requiresCudaDevices() throws Exception {
        System.setProperty("com.hotvect.direct_workers.nvidia_smi_output", "");

        JsonNode node = MAPPER.readTree("""
                {
                  "accelerator": "gpu"
                }
                """);

        assertThrows(IllegalArgumentException.class, () -> DirectWorkersConfig.fromJson(node, null));
    }

    @Test
    void fromJson_cuda_parentCudaVisibleDevicesDisableSentinelFailsWithHelpfulMessage() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {
                  "accelerator": "cuda"
                }
                """);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> DirectWorkersConfig.fromJson(node, "-1"));
        assertTrue(e.getMessage().contains("-1"));
    }

    @Test
    void fromJson_queueFullPolicy_defaultsToReject() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {
                  "accelerator": "cpu",
                  "workers_per_device": 1
                }
                """);

        DirectWorkersConfig cfg = DirectWorkersConfig.fromJson(node, null);
        assertEquals(QueueFullPolicy.REJECT, cfg.queueFullPolicy());
    }

    @Test
    void fromJson_queueFullPolicy_parsesCallerBlocksSynonyms() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {
                  "accelerator": "cpu",
                  "workers_per_device": 1,
                  "ipc": {
                    "queue_full_policy": "block"
                  }
                }
                """);

        DirectWorkersConfig cfg = DirectWorkersConfig.fromJson(node, null);
        assertEquals(QueueFullPolicy.CALLER_BLOCKS, cfg.queueFullPolicy());
    }

    @Test
    void fromJson_retry_defaultsToNoRetry() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {
                  "accelerator": "cpu",
                  "workers_per_device": 1
                }
                """);

        DirectWorkersConfig cfg = DirectWorkersConfig.fromJson(node, null);
        assertEquals(1, cfg.retryMaxAttempts());
        assertEquals(0L, cfg.retryBackoff().toMillis());
    }

    @Test
    void fromJson_retry_parsesNestedRetryConfig() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {
                  "accelerator": "cpu",
                  "workers_per_device": 1,
                  "ipc": {
                    "retry": {
                      "max_attempts": 3,
                      "backoff_ms": 25
                    }
                  }
                }
                """);

        DirectWorkersConfig cfg = DirectWorkersConfig.fromJson(node, null);
        assertEquals(3, cfg.retryMaxAttempts());
        assertEquals(25L, cfg.retryBackoff().toMillis());
    }
}
