package com.hotvect.onlineutils.hotdeploy;

import static org.junit.jupiter.api.Assertions.assertSame;

import com.hotvect.api.algorithms.Algorithm;
import java.net.URL;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrictChildFirstClassLoaderTest {
    @Test
    void usesContainerClassForTypesVisibleToParent() throws Exception {
        URL duplicateClasses = Algorithm.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation();
        try (StrictChildFirstClassLoader loader = new StrictChildFirstClassLoader(
                new URL[]{duplicateClasses},
                Algorithm.class.getClassLoader(),
                Set.of())) {
            assertSame(Algorithm.class, loader.loadClass(Algorithm.class.getName()));
        }
    }
}
