package com.eshioji.hotvect.hotdeploy;

import java.net.URL;
import java.nio.file.Path;

public class CloseableJarLoader {
    private CloseableJarLoader() {
    }

    public static CloseableJarHandle load(Path jarFile, ClassLoader parent) throws Exception {
        URL[] classpaths = new URL[]{jarFile.toUri().toURL()};
        try (var classLoader = new ChildFirstURLClassLoader(classpaths, parent)) {
            return new CloseableJarHandle(jarFile, classLoader);
        }
    }


}
