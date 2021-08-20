package com.eshioji.hotvect.hotdeploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;

public class JarAlgorithmLoader {
    private static final ObjectMapper OM = new ObjectMapper();
    private static final String ALGORITHM_DEFINITION_CONFIG_LOCATION = "algorithm_definition.json";

    private JarAlgorithmLoader() {
    }

    public static <R> AlgorithmDefinition<R> load(Path jarFile) {
        ChildFirstCloseableClassloader classLoader = null;
        try {
            classLoader = new ChildFirstCloseableClassloader(ImmutableList.of(jarFile.toUri().toURL()));

            AlgorithmMetadata algorithmMetadata = OM.readValue(readResource(classLoader, ALGORITHM_DEFINITION_CONFIG_LOCATION), AlgorithmMetadata.class);

            return new AlgorithmDefinition<>(jarFile, classLoader, algorithmMetadata);
        } catch (Throwable e) {
            // Attempt to close the class loader in any case to prevent memory leak
            try {
                if (classLoader != null) {
                    classLoader.close();
                }
            } catch (IOException e1) {
                throw new RuntimeException(e1);

            }
            throw new RuntimeException(e);
        }
    }

    private static String readResource(ClassLoader classLoader, String resourceName) throws IOException {
        try (InputStream is = classLoader.getResourceAsStream(resourceName)) {
            return CharStreams.toString(new InputStreamReader(is, Charsets.UTF_8));
        }
    }

}
