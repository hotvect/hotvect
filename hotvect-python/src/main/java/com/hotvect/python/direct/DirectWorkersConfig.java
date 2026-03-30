package com.hotvect.python.direct;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record DirectWorkersConfig(
        List<String> cudaVisibleDevicesPerWorker,
        Duration startupTimeout,
        Duration requestTimeout,
        Duration shutdownSigtermTimeout,
        Duration shutdownSigkillTimeout,
        Duration shutdownDescendantsTimeout,
        int workQueueSizePerWorker,
        QueueFullPolicy queueFullPolicy,
        int retryMaxAttempts,
        Duration retryBackoff,
        int maxFrameBytes,
        Path udsBaseDir
) {
    // Default values
    private static final int DEFAULT_STARTUP_TIMEOUT_MS = 60_000;
    private static final int DEFAULT_REQUEST_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_SIGTERM_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_SIGKILL_TIMEOUT_MS = 2_000;
    private static final int DEFAULT_DESCENDANTS_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_WORK_QUEUE_SIZE_PER_WORKER = 20;
    private static final QueueFullPolicy DEFAULT_QUEUE_FULL_POLICY = QueueFullPolicy.REJECT;
    private static final int DEFAULT_RETRY_MAX_ATTEMPTS = 1;
    private static final int DEFAULT_RETRY_BACKOFF_MS = 0;
    private static final int DEFAULT_MAX_FRAME_BYTES = 16 * 1024 * 1024;
    private static final String DEFAULT_UDS_BASE_DIR = "/tmp";

    public DirectWorkersConfig {
        Objects.requireNonNull(startupTimeout, "startupTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(shutdownSigtermTimeout, "shutdownSigtermTimeout");
        Objects.requireNonNull(shutdownSigkillTimeout, "shutdownSigkillTimeout");
        Objects.requireNonNull(shutdownDescendantsTimeout, "shutdownDescendantsTimeout");
        Objects.requireNonNull(udsBaseDir, "udsBaseDir");
        Objects.requireNonNull(queueFullPolicy, "queueFullPolicy");
        Objects.requireNonNull(retryBackoff, "retryBackoff");

        cudaVisibleDevicesPerWorker = List.copyOf(Objects.requireNonNull(cudaVisibleDevicesPerWorker, "cudaVisibleDevicesPerWorker"));
        if (cudaVisibleDevicesPerWorker.isEmpty()) {
            throw new IllegalArgumentException("cudaVisibleDevicesPerWorker must be non-empty");
        }
        for (String v : cudaVisibleDevicesPerWorker) {
            if (v == null) {
                throw new IllegalArgumentException("cudaVisibleDevicesPerWorker must not contain null entries");
            }
        }

        if (startupTimeout.isNegative() || startupTimeout.isZero()) {
            throw new IllegalArgumentException("startupTimeout must be > 0, got: " + startupTimeout);
        }
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be > 0, got: " + requestTimeout);
        }
        if (shutdownSigtermTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdownSigtermTimeout must be >= 0, got: " + shutdownSigtermTimeout);
        }
        if (shutdownSigkillTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdownSigkillTimeout must be >= 0, got: " + shutdownSigkillTimeout);
        }
        if (shutdownDescendantsTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdownDescendantsTimeout must be >= 0, got: " + shutdownDescendantsTimeout);
        }
        if (workQueueSizePerWorker < 1) {
            throw new IllegalArgumentException("workQueueSizePerWorker must be >= 1, got: " + workQueueSizePerWorker);
        }
        if (retryMaxAttempts < 1) {
            throw new IllegalArgumentException("retryMaxAttempts must be >= 1, got: " + retryMaxAttempts);
        }
        if (retryBackoff.isNegative()) {
            throw new IllegalArgumentException("retryBackoff must be >= 0, got: " + retryBackoff);
        }
        if (maxFrameBytes < 1024) {
            throw new IllegalArgumentException("maxFrameBytes must be >= 1024, got: " + maxFrameBytes);
        }
    }

    public int workerCount() {
        return cudaVisibleDevicesPerWorker.size();
    }

    public long totalQueueSize() {
        return Math.multiplyExact((long) workerCount(), (long) workQueueSizePerWorker);
    }

    /**
     * Creates a DirectWorkersConfig from a JSON configuration node.
     * <p>
     * Expected JSON structure:
     * <pre>
     * {
     *   "accelerator": "auto",           // "cpu", "cuda", "gpu", or "auto"
     *   "devices": "auto",               // "auto", int, or [int...]
     *   "workers_per_device": 1,         // int
     *   "startup_timeout_ms": 60000,
     *   "request_timeout_ms": 30000,
     *   "shutdown": {
     *     "sigterm_timeout_ms": 5000,
     *     "sigkill_timeout_ms": 2000,
     *     "descendants_timeout_ms": 10000
     *   },
     *   "ipc": {
     *     "work_queue_size_per_worker": 20,
     *     "queue_full_policy": "reject",  // "reject" or "caller_blocks"
     *     "retry": {
     *       "max_attempts": 1,
     *       "backoff_ms": 0
     *     },
     *     "max_frame_bytes": 16777216,
     *     "uds_base_dir": "/tmp"
     *   }
     * }
     * </pre>
     * <p>
     * If the parent process has CUDA_VISIBLE_DEVICES set, it is treated as a hard allowlist.
     * <p>
     * For CUDA, the set of "available devices" is:
     * <ul>
     *   <li>the parent allowlist (if set), in the order it appears in CUDA_VISIBLE_DEVICES</li>
     *   <li>otherwise, the device IDs detected from {@code nvidia-smi -L} (in order)</li>
     * </ul>
     * Device selection rules:
     * <ul>
     *   <li>{@code devices: "auto"} (or omitted) =&gt; all available devices</li>
     *   <li>{@code devices: 2} =&gt; first 2 available devices</li>
     *   <li>{@code devices: [0,2]} =&gt; device indices into the available list</li>
     * </ul>
     *
     * @param configNode config node
     * @return a configured DirectWorkersConfig
     */
    public static DirectWorkersConfig fromJson(JsonNode configNode) {
        return fromJson(configNode, System.getenv("CUDA_VISIBLE_DEVICES"));
    }

    static DirectWorkersConfig fromJson(JsonNode configNode, String parentCudaVisibleDevicesEnv) {
        Objects.requireNonNull(configNode, "configNode");
        boolean parentCudaVisibleDevicesPresent = parentCudaVisibleDevicesEnv != null;

        String acceleratorRaw = getStringOrDefault(configNode, "accelerator", "auto");
        String acceleratorNorm = acceleratorRaw == null ? "" : acceleratorRaw.trim().toLowerCase(Locale.ROOT);
        if (acceleratorNorm.isEmpty()) {
            acceleratorNorm = "auto";
        }
        if (!"cpu".equals(acceleratorNorm) && !"cuda".equals(acceleratorNorm) && !"gpu".equals(acceleratorNorm) && !"auto".equals(acceleratorNorm)) {
            throw new IllegalArgumentException("accelerator must be 'cpu', 'cuda', 'gpu', or 'auto', got: " + acceleratorRaw);
        }

        List<String> availableCudaVisibleDeviceTokens = List.of();
        if (!"cpu".equals(acceleratorNorm)) {
            availableCudaVisibleDeviceTokens = parentCudaVisibleDevicesPresent
                    ? CudaDeviceUtils.parseCudaVisibleDevicesAllowlist(parentCudaVisibleDevicesEnv)
                    : CudaDeviceUtils.detectCudaVisibleDeviceTokens();
        }

        String accelerator;
        if ("cpu".equals(acceleratorNorm)) {
            accelerator = "cpu";
        } else if ("auto".equals(acceleratorNorm)) {
            accelerator = availableCudaVisibleDeviceTokens.isEmpty() ? "cpu" : "cuda";
        } else {
            if (availableCudaVisibleDeviceTokens.isEmpty()) {
                String cause;
                if (!parentCudaVisibleDevicesPresent) {
                    cause = "nvidia-smi -L returned none";
                } else {
                    String trimmed = parentCudaVisibleDevicesEnv.trim();
                    if (trimmed.isEmpty()) {
                        cause = "CUDA_VISIBLE_DEVICES is set to empty string (no devices allowed)";
                    } else if (CudaDeviceUtils.isCudaVisibleDevicesDisableSentinel(trimmed)) {
                        cause = "CUDA_VISIBLE_DEVICES disables CUDA (value='" + trimmed + "')";
                    } else {
                        cause = "CUDA_VISIBLE_DEVICES does not allow any devices (value='" + parentCudaVisibleDevicesEnv + "')";
                    }
                }
                throw new IllegalArgumentException("accelerator='" + acceleratorNorm + "' requested but no CUDA devices available (" + cause + ")");
            }
            accelerator = "cuda";
        }

        Object devicesValue = parseDevicesValue(configNode.get("devices"));

        Integer workersPerDeviceValue = null;
        JsonNode workersPerDeviceNode = configNode.get("workers_per_device");
        if (workersPerDeviceNode != null && !workersPerDeviceNode.isNull()) {
            if (!(workersPerDeviceNode.isInt() || workersPerDeviceNode.isLong())) {
                throw new IllegalArgumentException("workers_per_device must be an integer, got: " + workersPerDeviceNode.getNodeType());
            }
            workersPerDeviceValue = workersPerDeviceNode.asInt();
        }
        int workersPerDevice = parseWorkersPerDevice(accelerator, workersPerDeviceValue);

        List<String> cudaVisibleDevicesPerWorker;
        if ("cpu".equals(accelerator)) {
            cudaVisibleDevicesPerWorker = CudaDeviceUtils.buildCudaVisibleDevicesPerWorkerTokens("cpu", List.of(), workersPerDevice);
        } else {
            List<String> selectedCudaVisibleDeviceTokens = CudaDeviceUtils.selectCudaVisibleDeviceTokens(
                    devicesValue,
                    availableCudaVisibleDeviceTokens
            );
            cudaVisibleDevicesPerWorker = CudaDeviceUtils.buildCudaVisibleDevicesPerWorkerTokens(
                    "cuda",
                    selectedCudaVisibleDeviceTokens,
                    workersPerDevice
            );
        }

        int startupTimeoutMs = getIntOrDefault(configNode, "startup_timeout_ms", DEFAULT_STARTUP_TIMEOUT_MS);
        Integer requestTimeoutMsOpt = getIntOrNull(configNode, "request_timeout_ms");
        Integer predictTimeoutMsOpt = getIntOrNull(configNode, "predict_timeout_ms");
        if (requestTimeoutMsOpt != null && predictTimeoutMsOpt != null) {
            throw new IllegalArgumentException("Only one of request_timeout_ms or predict_timeout_ms may be set");
        }
        int requestTimeoutMs = requestTimeoutMsOpt != null
                ? requestTimeoutMsOpt
                : (predictTimeoutMsOpt != null ? predictTimeoutMsOpt : DEFAULT_REQUEST_TIMEOUT_MS);

        JsonNode shutdownNode = configNode.get("shutdown");
        int sigtermTimeoutMs = getIntOrDefault(shutdownNode, "sigterm_timeout_ms", DEFAULT_SIGTERM_TIMEOUT_MS);
        int sigkillTimeoutMs = getIntOrDefault(shutdownNode, "sigkill_timeout_ms", DEFAULT_SIGKILL_TIMEOUT_MS);
        int descendantsTimeoutMs = getIntOrDefault(shutdownNode, "descendants_timeout_ms", DEFAULT_DESCENDANTS_TIMEOUT_MS);

        JsonNode ipcNode = configNode.get("ipc");
        int workQueueSizePerWorker = getIntOrDefault(ipcNode, "work_queue_size_per_worker", DEFAULT_WORK_QUEUE_SIZE_PER_WORKER);
        QueueFullPolicy queueFullPolicy = parseQueueFullPolicy(ipcNode);
        int retryMaxAttempts = parseRetryMaxAttempts(ipcNode);
        int retryBackoffMs = parseRetryBackoffMs(ipcNode);
        int maxFrameBytes = getIntOrDefault(ipcNode, "max_frame_bytes", DEFAULT_MAX_FRAME_BYTES);
        String udsBaseDir = getStringOrDefault(ipcNode, "uds_base_dir", DEFAULT_UDS_BASE_DIR);

        if (startupTimeoutMs < 1) {
            throw new IllegalArgumentException("startup_timeout_ms must be >= 1, got: " + startupTimeoutMs);
        }
        if (requestTimeoutMs < 1) {
            throw new IllegalArgumentException("request_timeout_ms must be >= 1, got: " + requestTimeoutMs);
        }
        if (sigtermTimeoutMs < 0) {
            throw new IllegalArgumentException("shutdown.sigterm_timeout_ms must be >= 0, got: " + sigtermTimeoutMs);
        }
        if (sigkillTimeoutMs < 0) {
            throw new IllegalArgumentException("shutdown.sigkill_timeout_ms must be >= 0, got: " + sigkillTimeoutMs);
        }
        if (descendantsTimeoutMs < 0) {
            throw new IllegalArgumentException("shutdown.descendants_timeout_ms must be >= 0, got: " + descendantsTimeoutMs);
        }
        if (workQueueSizePerWorker < 1) {
            throw new IllegalArgumentException("ipc.work_queue_size_per_worker must be >= 1, got: " + workQueueSizePerWorker);
        }
        if (retryMaxAttempts < 1) {
            throw new IllegalArgumentException("ipc.retry.max_attempts must be >= 1, got: " + retryMaxAttempts);
        }
        if (retryBackoffMs < 0) {
            throw new IllegalArgumentException("ipc.retry.backoff_ms must be >= 0, got: " + retryBackoffMs);
        }
        if (maxFrameBytes < 1024) {
            throw new IllegalArgumentException("ipc.max_frame_bytes must be >= 1024, got: " + maxFrameBytes);
        }
        if (udsBaseDir == null || udsBaseDir.isBlank()) {
            throw new IllegalArgumentException("ipc.uds_base_dir must be non-empty");
        }

        return new DirectWorkersConfig(
                cudaVisibleDevicesPerWorker,
                Duration.ofMillis(startupTimeoutMs),
                Duration.ofMillis(requestTimeoutMs),
                Duration.ofMillis(sigtermTimeoutMs),
                Duration.ofMillis(sigkillTimeoutMs),
                Duration.ofMillis(descendantsTimeoutMs),
                workQueueSizePerWorker,
                queueFullPolicy,
                retryMaxAttempts,
                Duration.ofMillis(retryBackoffMs),
                maxFrameBytes,
                Path.of(udsBaseDir)
        );
    }

    private static Object parseDevicesValue(JsonNode devicesNode) {
        if (devicesNode == null || devicesNode.isNull()) {
            return null;
        }
        if (devicesNode.isTextual()) {
            return devicesNode.asText();
        }
        if (devicesNode.isInt() || devicesNode.isLong()) {
            return devicesNode.asInt();
        }
        if (devicesNode.isArray()) {
            List<Integer> list = new ArrayList<>();
            for (JsonNode element : devicesNode) {
                if (element.isInt() || element.isLong()) {
                    list.add(element.asInt());
                } else {
                    throw new IllegalArgumentException("devices array must contain only integers");
                }
            }
            return list;
        }
        throw new IllegalArgumentException("devices must be 'auto', int, or int array");
    }

    private static int parseWorkersPerDevice(String accelerator, Integer workersPerDeviceValue) {
        if (workersPerDeviceValue == null) {
            return "cpu".equals(accelerator) ? Runtime.getRuntime().availableProcessors() : 1;
        }
        int workers = workersPerDeviceValue;
        if (workers < 1) {
            throw new IllegalArgumentException("workers_per_device must be >= 1, got: " + workers);
        }
        return workers;
    }

    private static String getStringOrDefault(JsonNode node, String field, String defaultValue) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return defaultValue;
        }
        JsonNode v = node.get(field);
        if (!v.isValueNode()) {
            throw new IllegalArgumentException(field + " must be a JSON scalar, got: " + v.getNodeType());
        }
        return v.asText(defaultValue);
    }

    private static Integer getIntOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode v = node.get(field);
        if (!(v.isInt() || v.isLong())) {
            throw new IllegalArgumentException(field + " must be an integer, got: " + v.getNodeType());
        }
        return v.asInt();
    }

    private static int getIntOrDefault(JsonNode node, String field, int defaultValue) {
        Integer v = getIntOrNull(node, field);
        return v != null ? v : defaultValue;
    }

    private static QueueFullPolicy parseQueueFullPolicy(JsonNode ipcNode) {
        String raw = getStringOrDefault(ipcNode, "queue_full_policy", DEFAULT_QUEUE_FULL_POLICY.name());
        String norm = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (norm.isEmpty()) {
            return DEFAULT_QUEUE_FULL_POLICY;
        }
        return switch (norm) {
            case "REJECT" -> QueueFullPolicy.REJECT;
            case "CALLER_BLOCKS", "CALLER_BLOCK", "BLOCK", "BLOCKING" -> QueueFullPolicy.CALLER_BLOCKS;
            default -> throw new IllegalArgumentException("ipc.queue_full_policy must be one of [reject, caller_blocks], got: " + raw);
        };
    }

    private static int parseRetryMaxAttempts(JsonNode ipcNode) {
        Integer legacy = getIntOrNull(ipcNode, "retry_max_attempts");
        if (legacy != null) {
            return legacy;
        }
        JsonNode retryNode = ipcNode == null ? null : ipcNode.get("retry");
        return getIntOrDefault(retryNode, "max_attempts", DEFAULT_RETRY_MAX_ATTEMPTS);
    }

    private static int parseRetryBackoffMs(JsonNode ipcNode) {
        Integer legacy = getIntOrNull(ipcNode, "retry_backoff_ms");
        if (legacy != null) {
            return legacy;
        }
        JsonNode retryNode = ipcNode == null ? null : ipcNode.get("retry");
        return getIntOrDefault(retryNode, "backoff_ms", DEFAULT_RETRY_BACKOFF_MS);
    }
}
