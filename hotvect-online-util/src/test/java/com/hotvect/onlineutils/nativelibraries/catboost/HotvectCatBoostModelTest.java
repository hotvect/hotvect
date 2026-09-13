package com.hotvect.onlineutils.nativelibraries.catboost;

import com.hotvect.onlineutils.hotdeploy.StrictChildFirstClassLoader;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

class HotvectCatBoostModelTest {
    @Test
    void cleanerDoesNotRetainTheArtifactThatInitializesIt() throws Exception {
        // Give the shared model class a fresh lifetime so other tests cannot warm up its cleaner.
        try (StrictChildFirstClassLoader host = new StrictChildFirstClassLoader(
                new URL[]{HotvectCatBoostModel.class.getProtectionDomain().getCodeSource().getLocation()},
                getClass().getClassLoader(),
                Set.of(HotvectCatBoostModel.class.getName()))) {
            ReferenceQueue<ClassLoader> collectedLoaders = new ReferenceQueue<>();
            WeakReference<ClassLoader> artifact = initializeFromArtifact(host, collectedLoaders);
            Reference<? extends ClassLoader> collected = null;
            for (int attempt = 0; attempt < 100 && collected == null; attempt++) {
                System.gc();
                collected = collectedLoaders.remove(100);
            }
            assertSame(artifact, collected, "Cleaner must not retain the retired artifact classloader");
            // The artifact must unload while the host and its static cleaner remain alive.
            Reference.reachabilityFence(host);
        }
    }

    private static WeakReference<ClassLoader> initializeFromArtifact(
            ClassLoader host,
            ReferenceQueue<ClassLoader> collectedLoaders) throws Exception {
        try (StrictChildFirstClassLoader artifact = new StrictChildFirstClassLoader(
                new URL[]{ArtifactInitializer.class.getProtectionDomain().getCodeSource().getLocation()},
                host,
                Set.of(ArtifactInitializer.class.getName()))) {
            Class<?> initializerClass = artifact.loadClass(ArtifactInitializer.class.getName());
            assertSame(artifact, initializerClass.getClassLoader());
            ((Runnable) initializerClass.getConstructor().newInstance()).run();
            return new WeakReference<>(artifact, collectedLoaders);
        }
    }

    public static final class ArtifactInitializer implements Runnable {
        @Override
        public void run() {
            try {
                Class.forName(HotvectCatBoostModel.class.getName(), true, getClass().getClassLoader());
            } catch (ClassNotFoundException error) {
                throw new AssertionError(error);
            }
        }
    }
}
