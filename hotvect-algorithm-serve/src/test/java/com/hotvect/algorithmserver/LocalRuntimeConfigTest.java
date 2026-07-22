package com.hotvect.algorithmserver;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRuntimeConfigTest {
    @Test
    void rejectsUnknownFields() throws Exception {
        Path dir = Files.createTempDirectory("local-runtime-config-unknown");
        Path configPath = dir.resolve("local-runtimes.json");
        Files.writeString(
                configPath,
                """
                        {
                          "runtimes": [
                            {
                              "algorithm_jar": "algo.jar",
                              "algorithm_name": "demo-algo",
                              "parameter_path": "params.zip",
                              "unexpected": "value"
                            }
                          ]
                        }
                        """,
                StandardCharsets.UTF_8);

        Exception error = assertThrows(Exception.class, () -> LocalRuntimeConfig.load(configPath.toFile()));
        assertTrue(error.getMessage().contains("unexpected"));
    }

    @Test
    void resolvesRelativePathsAgainstConfigDirectory() throws Exception {
        Path root = Files.createTempDirectory("local-runtime-config-relative");
        Path configDir = Files.createDirectories(root.resolve("configs"));
        Path configPath = configDir.resolve("local-runtimes.json");
        Files.writeString(
                configPath,
                """
                        {
                          "runtimes": [
                            {
                              "algorithm_jar": "../algorithms/demo.jar",
                              "algorithm_name": "demo-algo",
                              "algorithm_override": "../overrides/demo.json",
                              "parameter_path": "../params/demo.zip"
                            }
                          ]
                        }
                        """,
                StandardCharsets.UTF_8);

        LocalRuntimeConfig config = LocalRuntimeConfig.load(configPath.toFile());
        LocalRuntimeConfig.RuntimeSpec runtime = config.runtimes().getFirst();

        assertEquals(root.resolve("algorithms/demo.jar").normalize().toString(), runtime.algorithmJar().toPath().normalize().toString());
        assertEquals(root.resolve("overrides/demo.json").normalize().toString(), runtime.algorithmOverride().toPath().normalize().toString());
        assertEquals(root.resolve("params/demo.zip").normalize().toString(), runtime.parameterPath().toPath().normalize().toString());
    }
}
