package com.hotvect.serve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.utils.AlgorithmDefinitionReader;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlgorithmRuntimeOverrideTest {
    @TempDir
    Path tempDir;

    @Test
    void appliesDefinitionAndDeclaredDependencySettings() throws Exception {
        AlgorithmDefinition effective = AlgorithmRuntime.applyAlgorithmOverride(
                baseDefinition(),
                override("""
                        {
                          "algorithm_parameters": {"threshold": 2},
                          "dependencies": {
                            "child": {"algorithm_parameters": {"weight": 0.8}}
                          }
                        }
                        """));

        assertEquals(2, effective.algorithmParameter().orElseThrow().path("threshold").asInt());
        assertEquals(
                0.8,
                effective.rawAlgorithmDefinition()
                        .path("dependencies")
                        .path("child")
                        .path("algorithm_parameters")
                        .path("weight")
                        .asDouble());
    }

    @Test
    void appliesOfflineOverridesToSharedDependencies() throws Exception {
        AlgorithmDefinition effective = AlgorithmRuntime.applyAlgorithmOverride(
                offlineSharedBaseDefinition(),
                override("""
                        {
                          "dependencies": {
                            "shared-child": {"algorithm_parameters": {"weight": 0.8}}
                          }
                        }
                        """));

        assertEquals(
                0.8,
                effective.rawAlgorithmDefinition()
                        .path("dependencies")
                        .path("shared-child")
                        .path("algorithm_parameters")
                        .path("weight")
                        .asDouble());
    }

    @Test
    void rejectsUnrelatedTopLevelFields() throws Exception {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmRuntime.applyAlgorithmOverride(
                        baseDefinition(),
                        override("{\"runtimes\": []}")));

        assertEquals("Unknown algorithm definition override fields: [runtimes]", error.getMessage());
    }

    @Test
    void rejectsRootIdentityChanges() throws Exception {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmRuntime.applyAlgorithmOverride(
                        baseDefinition(),
                        override("{\"algorithm_version\": \"999\"}")));

        assertEquals(
                "Algorithm definition override must not contain identity field: algorithm_version",
                error.getMessage());
    }

    @Test
    void rejectsUnknownDependencies() throws Exception {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmRuntime.applyAlgorithmOverride(
                        baseDefinition(),
                        override("{\"dependencies\": {\"typo\": {}}}")));

        assertEquals("Override references unknown dependency: typo", error.getMessage());
    }

    private File override(String json) throws Exception {
        Path path = tempDir.resolve("override.json");
        Files.writeString(path, json);
        return path.toFile();
    }

    private static AlgorithmDefinition baseDefinition() throws Exception {
        return new AlgorithmDefinitionReader().parse("""
                {
                  "algorithm_name": "root",
                  "algorithm_version": "1",
                  "algorithm_factory_classname": "example.RootFactory",
                  "dependencies": {"child": {}}
                }
                """);
    }

    private static AlgorithmDefinition offlineSharedBaseDefinition() throws Exception {
        return new AlgorithmDefinitionReader(AlgorithmDefinitionReader.DependencyResolution.OFFLINE).parse("""
                {
                  "algorithm_name": "root",
                  "algorithm_version": "1",
                  "algorithm_factory_classname": "example.RootFactory",
                  "dependencies": {"shared-child@2": {"scope": "shared"}}
                }
                """);
    }
}
