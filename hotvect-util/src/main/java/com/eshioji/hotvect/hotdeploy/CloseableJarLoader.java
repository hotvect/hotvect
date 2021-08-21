package com.eshioji.hotvect.hotdeploy;

import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Path;

public class CloseableJarLoader {
    private CloseableJarLoader() {
    }

    public static CloseableJarHandle load(Path jarFile) {
        ChildFirstCloseableClassloader classLoader = null;
        try {
            classLoader = new ChildFirstCloseableClassloader(ImmutableList.of(jarFile.toUri().toURL()));
            return new CloseableJarHandle(jarFile, classLoader);
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


}
