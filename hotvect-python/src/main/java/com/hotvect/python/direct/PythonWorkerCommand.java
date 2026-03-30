package com.hotvect.python.direct;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record PythonWorkerCommand(
        String pythonExecutable,
        String pythonModule,
        List<String> args,
        Path workingDirectory,
        Map<String, String> environment
) {
    public PythonWorkerCommand {
        if (pythonExecutable == null || pythonExecutable.isBlank()) {
            throw new IllegalArgumentException("pythonExecutable must be non-empty");
        }
        if (pythonModule == null || pythonModule.isBlank()) {
            throw new IllegalArgumentException("pythonModule must be non-empty");
        }

        args = args == null ? List.of() : List.copyOf(args);
        if (environment != null && environment.containsKey("CUDA_VISIBLE_DEVICES")) {
            throw new IllegalArgumentException("environment must not set CUDA_VISIBLE_DEVICES; it is managed by direct workers");
        }
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }

    public PythonWorkerCommand(String pythonExecutable, String pythonModule) {
        this(pythonExecutable, pythonModule, List.of(), null, Map.of());
    }

    public PythonWorkerCommand(String pythonExecutable, String pythonModule, List<String> args) {
        this(pythonExecutable, pythonModule, args, null, Map.of());
    }

    public PythonWorkerCommand(String pythonExecutable, String pythonModule, List<String> args, Map<String, String> environment) {
        this(pythonExecutable, pythonModule, args, null, environment);
    }
}
