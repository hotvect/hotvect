package com.eshioji.hotvect.hotdeploy;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class CloseableJarHandle implements Closeable {
    private final ChildFirstURLClassLoader classLoader;
    private final Path jarFile;

    public CloseableJarHandle(Path jarFile, ChildFirstURLClassLoader classLoader) {
        this.jarFile = jarFile;
        this.classLoader = classLoader;
    }

    @Override
    public void close() throws IOException {
        try {
            this.classLoader.close();
        } finally {
            if (jarFile != null) {
                Files.deleteIfExists(jarFile);
            }
        }
    }

    public ChildFirstURLClassLoader getClassLoader() {
        return classLoader;
    }
}

