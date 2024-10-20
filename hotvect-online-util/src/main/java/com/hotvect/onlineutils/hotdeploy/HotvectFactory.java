package com.hotvect.onlineutils.hotdeploy;

import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

import static com.google.common.base.Preconditions.checkArgument;

public abstract class HotvectFactory {
    protected final ClassLoader classLoader;

    protected HotvectFactory(File algorithmJar, ClassLoader parent) throws MalformedAlgorithmException {
        try {
            checkArgument(algorithmJar.exists() && algorithmJar.isFile(), "Specified algorithm jar does not exist or is not a file:%s", algorithmJar.getAbsolutePath());
            URL algorithmJarUrl = algorithmJar.toURI().toURL();
            if (parent == null) {
                this.classLoader = new URLClassLoader(new URL[]{algorithmJarUrl});
            } else {
                this.classLoader = new URLClassLoader(new URL[]{algorithmJarUrl}, parent);
            }
        } catch (Exception e) {
            throw new MalformedAlgorithmException(e);
        }
    }

    protected HotvectFactory(File algorithmJar) throws MalformedAlgorithmException {
        this(algorithmJar, Thread.currentThread().getContextClassLoader());
    }

    protected HotvectFactory(ClassLoader classLoader) throws MalformedAlgorithmException {
        this.classLoader = classLoader;
    }
}
