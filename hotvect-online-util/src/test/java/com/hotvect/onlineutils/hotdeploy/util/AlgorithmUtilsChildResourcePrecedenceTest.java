package com.hotvect.onlineutils.hotdeploy.util;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.onlineutils.hotdeploy.StrictChildFirstClassLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlgorithmUtilsChildResourcePrecedenceTest {

    @TempDir
    Path tmpDir;

    @Test
    void readAlgorithmDefinitionFromClassLoader_prefersChildJarResourceWhenParentHasSameName() throws Exception {
        Path parentJar = createJar(
                tmpDir.resolve("parent.jar"),
                "example-feature-state-algorithm-definition.json",
                algorithmDefinitionJson("example-feature-state", "parent-version", "parent.Factory")
        );
        Path childJar = createJar(
                tmpDir.resolve("child.jar"),
                "example-feature-state-algorithm-definition.json",
                algorithmDefinitionJson("example-feature-state", "child-version", "child.Factory")
        );

        try (URLClassLoader parent = new URLClassLoader(new URL[]{parentJar.toUri().toURL()}, null);
             StrictChildFirstClassLoader child =
                     new StrictChildFirstClassLoader(new URL[]{childJar.toUri().toURL()}, parent, Set.of())) {
            assertTrue(
                    child.getResource("example-feature-state-algorithm-definition.json").toString().contains("child.jar"),
                    "expected child classloader resource lookup to prefer the algorithm jar"
            );

            AlgorithmDefinition definition =
                    AlgorithmUtils.readAlgorithmDefinitionFromClassLoader("example-feature-state", child);
            assertEquals("child-version", definition.algorithmId().algorithmVersion());
            assertEquals("child.Factory", definition.algorithmFactoryName());
        }
    }

    private static Path createJar(Path jarPath, String resourceName, String contents) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath))) {
            output.putNextEntry(new JarEntry(resourceName));
            output.write(contents.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jarPath;
    }

    private static String algorithmDefinitionJson(String algorithmName, String algorithmVersion, String factoryClassName) {
        return """
                {
                  "algorithm_name": "%s",
                  "algorithm_version": "%s",
                  "algorithm_factory_classname": "%s"
                }
                """.formatted(algorithmName, algorithmVersion, factoryClassName);
    }
}
