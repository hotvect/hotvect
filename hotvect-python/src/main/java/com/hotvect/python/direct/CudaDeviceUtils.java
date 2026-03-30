package com.hotvect.python.direct;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Utilities for detecting and configuring CUDA devices for direct workers.
 */
public final class CudaDeviceUtils {
    private CudaDeviceUtils() {
    }

    static boolean isCudaVisibleDevicesDisableSentinel(String visible) {
        if (visible == null) {
            return false;
        }
        String lowered = visible.trim().toLowerCase(Locale.ROOT);
        return "-1".equals(lowered) || "nodevfiles".equals(lowered);
    }

    /**
     * Parses CUDA_VISIBLE_DEVICES allowlist from the parent process environment.
     * <p>
     * Returns an empty list when CUDA_VISIBLE_DEVICES is unset/blank, or when it is set to a common
     * "disable GPU" sentinel value (e.g., "-1" or "NoDevFiles").
     *
     * @param visible raw CUDA_VISIBLE_DEVICES string, or null
     * @return list of allowlisted device tokens (e.g., ["0","1"] or ["GPU-..."]), or empty when none
     */
    public static List<String> parseCudaVisibleDevicesAllowlist(String visible) {
        if (visible == null) {
            return List.of();
        }

        String trimmed = visible.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }

        if (isCudaVisibleDevicesDisableSentinel(trimmed)) {
            return List.of();
        }

        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String token : trimmed.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (isCudaVisibleDevicesDisableSentinel(t)) {
                continue;
            }
            if (!seen.add(t)) {
                throw new IllegalArgumentException("CUDA_VISIBLE_DEVICES must not contain duplicates, got: " + visible);
            }
            out.add(t);
        }
        return out;
    }

    static List<String> detectCudaVisibleDeviceTokens() {
        return detectCudaDeviceIds().stream().map(String::valueOf).toList();
    }

    /**
     * Detects available CUDA device IDs by running nvidia-smi -L.
     *
     * @return list of device IDs (e.g., [0, 1, 2]), or empty if no CUDA devices
     */
    public static List<Integer> detectCudaDeviceIds() {
        String mock = System.getProperty("com.hotvect.direct_workers.nvidia_smi_output");
        if (mock != null) {
            return parseNvidiaSmiGpuList(mock);
        }

        Process process;
        try {
            process = new ProcessBuilder("nvidia-smi", "-L")
                    .redirectErrorStream(true)
                    .start();
        } catch (Exception e) {
            return List.of();
        }

        try {
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return List.of();
            }
            if (process.exitValue() != 0) {
                return List.of();
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return parseNvidiaSmiGpuList(stdout);
        } catch (Exception e) {
            return List.of();
        } finally {
            process.destroy();
        }
    }

    /**
     * Parses nvidia-smi -L output to extract GPU device IDs.
     * Example line: "GPU 0: NVIDIA A10G (UUID: GPU-....)"
     */
    public static List<Integer> parseNvidiaSmiGpuList(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        Set<Integer> seen = new HashSet<>();
        List<Integer> out = new ArrayList<>();
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("GPU")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String beforeColon = trimmed.substring(0, colon).trim(); // "GPU 0"
            String[] parts = beforeColon.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            String idToken = parts[1].trim();
            if (!idToken.chars().allMatch(Character::isDigit)) {
                continue;
            }
            int id = Integer.parseInt(idToken);
            if (id < 0) {
                continue;
            }
            if (seen.add(id)) {
                out.add(id);
            }
        }
        return out;
    }

    static List<String> selectCudaVisibleDeviceTokens(Object devicesValue, List<String> availableCudaVisibleDeviceTokens) {
        Objects.requireNonNull(availableCudaVisibleDeviceTokens, "availableCudaVisibleDeviceTokens");
        if (availableCudaVisibleDeviceTokens.isEmpty()) {
            throw new IllegalArgumentException("No CUDA devices available");
        }

        if (devicesValue == null) {
            return availableCudaVisibleDeviceTokens;
        }

        if (devicesValue instanceof String s) {
            String v = s.trim().toLowerCase(Locale.ROOT);
            if ("auto".equals(v)) {
                return availableCudaVisibleDeviceTokens;
            }
            throw new IllegalArgumentException("devices must be an int, int array, or 'auto' when accelerator='cuda', got string: " + s);
        }

        if (devicesValue instanceof Number n) {
            int count = n.intValue();
            if (count < 1) {
                throw new IllegalArgumentException("devices must be >= 1 when accelerator='cuda', got: " + count);
            }
            if (count > availableCudaVisibleDeviceTokens.size()) {
                throw new IllegalArgumentException(
                        "devices is greater than available CUDA device count: requested=" + count
                                + " available=" + availableCudaVisibleDeviceTokens.size()
                );
            }
            return availableCudaVisibleDeviceTokens.subList(0, count);
        }

        if (devicesValue instanceof List<?> list) {
            if (list.isEmpty()) {
                throw new IllegalArgumentException("devices must be a non-empty list when accelerator='cuda'");
            }
            Set<Integer> seenIndices = new HashSet<>();
            List<String> out = new ArrayList<>();
            for (Object element : list) {
                if (!(element instanceof Number n)) {
                    throw new IllegalArgumentException("devices list must contain only integers, got: " + element);
                }
                int index = n.intValue();
                if (index < 0) {
                    throw new IllegalArgumentException("devices values must be >= 0, got: " + index);
                }
                if (!seenIndices.add(index)) {
                    throw new IllegalArgumentException("devices must not contain duplicates, got: " + index);
                }
                if (index >= availableCudaVisibleDeviceTokens.size()) {
                    throw new IllegalArgumentException(
                            "devices references a CUDA device index that is not available: index=" + index
                                    + " available=" + availableCudaVisibleDeviceTokens.size()
                    );
                }
                out.add(availableCudaVisibleDeviceTokens.get(index));
            }
            return out;
        }

        throw new IllegalArgumentException("devices must be 'auto', int, or int array, got: " + devicesValue.getClass().getSimpleName());
    }

    static List<String> buildCudaVisibleDevicesPerWorkerTokens(
            String accelerator,
            List<String> cudaVisibleDeviceTokens,
            int workersPerDevice
    ) {
        if (workersPerDevice < 1) {
            throw new IllegalArgumentException("workersPerDevice must be >= 1, got: " + workersPerDevice);
        }

        List<String> out = new ArrayList<>();
        if ("cpu".equals(accelerator)) {
            for (int i = 0; i < workersPerDevice; i++) {
                out.add("");
            }
            return out;
        }

        if (cudaVisibleDeviceTokens == null || cudaVisibleDeviceTokens.isEmpty()) {
            throw new IllegalArgumentException("cudaVisibleDeviceTokens must be non-empty for CUDA accelerator");
        }
        for (String token : cudaVisibleDeviceTokens) {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("cudaVisibleDeviceTokens must be non-blank");
            }
            for (int j = 0; j < workersPerDevice; j++) {
                out.add(token);
            }
        }
        return out;
    }
}
