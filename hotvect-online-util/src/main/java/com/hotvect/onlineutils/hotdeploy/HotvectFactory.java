package com.hotvect.onlineutils.hotdeploy;

import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;

import java.io.File;
import java.net.URL;
import java.util.Objects;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;

public abstract class HotvectFactory {

    /** Parent-first class loader with child-first resources and a private algorithm Namespaces class. */
    protected final ClassLoader classLoader;

    /** Creates one isolated artifact classloader with an explicit common parent. */
    protected HotvectFactory(File algorithmJar, ClassLoader parent) throws MalformedAlgorithmException {
        this.classLoader = newAlgorithmClassLoader(algorithmJar, parent);
    }

    protected HotvectFactory(File algorithmJar) throws MalformedAlgorithmException {
        this(algorithmJar, Thread.currentThread().getContextClassLoader());
    }

    protected HotvectFactory(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader must not be null");
    }

    /**
     * Creates the classloader used for one artifact in a direct graph.
     *
     * <p>Null parents are intentionally rejected. Passing null used to let the JVM select the
     * system loader after validation had already happened, making the classloading boundary
     * dependent on an invisible fallback.</p>
     */
    static StrictChildFirstClassLoader newAlgorithmClassLoader(File algorithmJar, ClassLoader parent) {
        Objects.requireNonNull(algorithmJar, "algorithmJar must not be null");
        Objects.requireNonNull(parent, "parent classLoader must not be null");
        try {
            checkArgument(algorithmJar.exists() && algorithmJar.isFile(),
                    "Specified algorithm jar does not exist or is not a file: %s",
                    algorithmJar.getAbsolutePath());
            URL jarUrl = algorithmJar.toURI().toURL();
            return new StrictChildFirstClassLoader(
                    new URL[]{jarUrl},
                    parent,
                    Set.of("com.hotvect.core.transform.Namespaces"));
        } catch (MalformedAlgorithmException error) {
            throw error;
        } catch (Exception error) {
            throw new MalformedAlgorithmException(error);
        }
    }
}
