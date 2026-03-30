package com.hotvect.python.direct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class PythonProcessLauncher {
    private static final Logger log = LoggerFactory.getLogger(PythonProcessLauncher.class);
    private static final String HOTVECT_PYTHON_EXECUTABLE_ENV = "HOTVECT_PYTHON_EXECUTABLE";

    private PythonProcessLauncher() {
    }

    static Process startDirectWorker(
            PythonWorkerCommand command,
            Path connectUdsPath,
            int maxFrameBytes,
            int workerIndex,
            Map<String, String> envOverrides
    ) {
        List<String> cmd = new ArrayList<>(4 + command.args().size() + 6);
        cmd.add(command.pythonExecutable());
        cmd.add("-u");
        cmd.add("-m");
        cmd.add(command.pythonModule());
        cmd.addAll(command.args());

        cmd.add("--connect-uds-path");
        cmd.add(connectUdsPath.toString());
        cmd.add("--max-frame-bytes");
        cmd.add(Integer.toString(maxFrameBytes));
        cmd.add("--worker-index");
        cmd.add(Integer.toString(workerIndex));

        return startProcess(cmd, command.workingDirectory(), command.environment(), envOverrides);
    }

    private static Process startProcess(
            List<String> cmd,
            Path workingDirectory,
            Map<String, String> baseEnv,
            Map<String, String> envOverrides
    ) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
            pb.redirectError(ProcessBuilder.Redirect.PIPE);
            if (workingDirectory != null) {
                pb.directory(workingDirectory.toFile());
            }

            if (baseEnv != null && !baseEnv.isEmpty()) {
                pb.environment().putAll(baseEnv);
            }
            if (envOverrides != null && !envOverrides.isEmpty()) {
                pb.environment().putAll(envOverrides);
            }

            pb.environment().putIfAbsent("PYTHONUNBUFFERED", "1");

            String resolvedPythonExe = resolveExecutable(pb.command().get(0), pb.environment());
            if (!resolvedPythonExe.equals(pb.command().get(0))) {
                pb.command().set(0, resolvedPythonExe);
            }

            log.info("Starting direct python worker: {}", toShellCommand(pb.command(), pb.environment()));
            return pb.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start direct python worker: " + cmd, e);
        }
    }

    private static String toShellCommand(List<String> command, Map<String, String> env) {
        StringBuilder envPrefix = new StringBuilder();
        envPrefix.append("PYTHONUNBUFFERED=").append(shellEscape(env.get("PYTHONUNBUFFERED")));

        String cudaVisibleDevices = env.get("CUDA_VISIBLE_DEVICES");
        if (cudaVisibleDevices != null) {
            envPrefix.append(" CUDA_VISIBLE_DEVICES=").append(shellEscape(cudaVisibleDevices));
        }

        List<String> safeEnvToLog = List.of(
                "TF_NUM_INTRAOP_THREADS",
                "TF_NUM_INTEROP_THREADS",
                "OMP_NUM_THREADS",
                "MKL_NUM_THREADS",
                "KMP_AFFINITY",
                "OMP_PROC_BIND",
                "OMP_PLACES"
        );
        for (String key : safeEnvToLog) {
            String value = env.get(key);
            if (value != null) {
                envPrefix.append(" ").append(key).append("=").append(shellEscape(value));
            }
        }

        return envPrefix + " " + String.join(" ", command.stream().map(PythonProcessLauncher::shellEscape).toList());
    }

    static String resolveExecutable(String executable, Map<String, String> env) {
        if (executable == null || executable.isBlank()) {
            return executable;
        }

        try {
            Path explicit = Path.of(executable);
            if (explicit.isAbsolute() || executable.contains("/")) {
                if (Files.isExecutable(explicit)) {
                    return explicit.toAbsolutePath().toString();
                }
                return executable;
            }

            String hotvectPython = env != null ? env.get(HOTVECT_PYTHON_EXECUTABLE_ENV) : null;
            if (hotvectPython == null || hotvectPython.isBlank()) {
                hotvectPython = System.getenv(HOTVECT_PYTHON_EXECUTABLE_ENV);
            }
            if (hotvectPython != null && !hotvectPython.isBlank()) {
                Path hotvectPythonPath = Path.of(hotvectPython);
                if ((hotvectPythonPath.isAbsolute() || hotvectPython.contains("/"))
                        && Files.isExecutable(hotvectPythonPath)) {
                    return hotvectPythonPath.toAbsolutePath().toString();
                }
            }

            String pathEnv = env != null ? env.get("PATH") : null;
            if (pathEnv == null || pathEnv.isBlank()) {
                pathEnv = System.getenv("PATH");
            }
            if (pathEnv == null || pathEnv.isBlank()) {
                return executable;
            }

            for (String dir : pathEnv.split(":")) {
                if (dir == null || dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir, executable);
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        } catch (Exception ignored) {
            // Best-effort
        }
        return executable;
    }

    private static String shellEscape(String value) {
        if (value == null) {
            return "''";
        }
        if (value.isEmpty()) {
            return "''";
        }
        if (value.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '/' || c == ':')) {
            return value;
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
