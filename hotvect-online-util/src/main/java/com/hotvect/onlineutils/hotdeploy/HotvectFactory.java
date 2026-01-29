package com.hotvect.onlineutils.hotdeploy;

import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;

import java.io.File;
import java.net.URL;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;

public abstract class HotvectFactory {

    /** Child-first loader that isolates algorithm classes and enforces private Namespaces. */
    protected final ClassLoader classLoader;

    /**
     * @param algorithmJar  the algorithm JAR file
     * @param parent        parent class-loader; may be {@code null}
     * @throws MalformedAlgorithmException wrapped I/O or class-loading errors
     */
    protected HotvectFactory(File algorithmJar, ClassLoader parent) throws MalformedAlgorithmException {
        try {
            checkArgument(algorithmJar.exists() && algorithmJar.isFile(),
                    "Specified algorithm jar does not exist or is not a file: %s",
                    algorithmJar.getAbsolutePath());

            URL jarUrl = algorithmJar.toURI().toURL();
            Set<String> required = Set.of("com.hotvect.core.transform.Namespaces");

            if (parent == null) {
                this.classLoader =
                        new StrictChildFirstClassLoader(new URL[]{jarUrl}, required);
            } else {
                this.classLoader =
                        new StrictChildFirstClassLoader(new URL[]{jarUrl}, parent, required);
            }
        } catch (Exception e) {
            throw new MalformedAlgorithmException(e);
        }
    }

    protected HotvectFactory(File algorithmJar) throws MalformedAlgorithmException {
        this(algorithmJar, Thread.currentThread().getContextClassLoader());
    }

    protected HotvectFactory(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }
}
