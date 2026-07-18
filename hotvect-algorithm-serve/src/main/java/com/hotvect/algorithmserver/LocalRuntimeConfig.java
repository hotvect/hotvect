package com.hotvect.algorithmserver;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

final class LocalRuntimeConfig {
    private static final ObjectMapper OM = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private final List<RuntimeSpec> runtimes;

    private LocalRuntimeConfig(List<RuntimeSpec> runtimes) {
        this.runtimes = List.copyOf(runtimes);
    }

    static LocalRuntimeConfig load(File configPath) throws Exception {
        Objects.requireNonNull(configPath, "configPath must not be null");
        Path baseDir = configPath.toPath().toAbsolutePath().normalize().getParent();
        if (baseDir == null) {
            baseDir = Path.of(".").toAbsolutePath().normalize();
        }
        Path resolvedBaseDir = baseDir;
        RawLocalRuntimeConfig raw = OM.readValue(configPath, RawLocalRuntimeConfig.class);
        if (raw == null || raw.runtimes == null || raw.runtimes.isEmpty()) {
            throw new IllegalArgumentException("--local-runtime-config must define at least one runtime");
        }
        return new LocalRuntimeConfig(raw.runtimes.stream()
                .map(spec -> RuntimeSpec.fromRaw(spec, resolvedBaseDir))
                .toList());
    }

    List<RuntimeSpec> runtimes() {
        return runtimes;
    }

    record RuntimeSpec(
            File algorithmJar,
            String algorithmName,
            File algorithmOverride,
            File parameterPath
    ) {
        RuntimeSpec {
            Objects.requireNonNull(algorithmJar, "algorithm_jar must not be null");
            Objects.requireNonNull(parameterPath, "parameter_path must not be null");
            algorithmName = requireNonBlank(algorithmName, "algorithm_name");
        }

        private static RuntimeSpec fromRaw(RawRuntimeSpec raw, Path baseDir) {
            if (raw == null) {
                throw new IllegalArgumentException("--local-runtime-config contains a null runtime entry");
            }
            return new RuntimeSpec(
                    resolveRelativePath(raw.algorithmJar, baseDir),
                    raw.algorithmName,
                    resolveRelativePath(raw.algorithmOverride, baseDir),
                    resolveRelativePath(raw.parameterPath, baseDir));
        }
    }

    private static File resolveRelativePath(File pathOrNull, Path baseDir) {
        if (pathOrNull == null) {
            return null;
        }
        Path path = pathOrNull.toPath();
        if (path.isAbsolute()) {
            return path.normalize().toFile();
        }
        return baseDir.resolve(path).normalize().toFile();
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--local-runtime-config field must not be blank: " + fieldName);
        }
        return value;
    }

    private static final class RawLocalRuntimeConfig {
        @JsonProperty("runtimes")
        public List<RawRuntimeSpec> runtimes;
    }

    private static final class RawRuntimeSpec {
        @JsonProperty("algorithm_jar")
        public File algorithmJar;
        @JsonProperty("algorithm_name")
        public String algorithmName;
        @JsonProperty("algorithm_override")
        public File algorithmOverride;
        @JsonProperty("parameter_path")
        public File parameterPath;
    }
}
