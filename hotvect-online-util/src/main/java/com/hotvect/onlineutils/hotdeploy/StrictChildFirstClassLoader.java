package com.hotvect.onlineutils.hotdeploy;

import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

public final class StrictChildFirstClassLoader extends URLClassLoader {

    private final Set<String> requiredChildClasses;

    public StrictChildFirstClassLoader(URL[] urls, ClassLoader parent, Collection<String> requiredChildClasses) {
        super(urls, parent);
        this.requiredChildClasses = ImmutableSet.copyOf(requiredChildClasses);
    }

    public StrictChildFirstClassLoader(URL[] urls, Collection<String> requiredChildClasses) {
        super(urls);
        this.requiredChildClasses = ImmutableSet.copyOf(requiredChildClasses);
    }

    @Override
    public URL getResource(String name) {
        URL childResource = findResource(name);
        if (childResource != null) {
            return childResource;
        }
        ClassLoader parent = getParent();
        return parent != null ? parent.getResource(name) : ClassLoader.getSystemResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        LinkedHashSet<URL> resources = new LinkedHashSet<>();
        addAll(resources, findResources(name));
        ClassLoader parent = getParent();
        addAll(resources, parent != null ? parent.getResources(name) : ClassLoader.getSystemResources(name));
        return Collections.enumeration(resources);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> cls = findLoadedClass(name);
            if (cls != null) {
                return cls;
            }

            boolean mustLoadInChild = requiredChildClasses.contains(name);

            if (!mustLoadInChild) {
                return super.loadClass(name, resolve);
            }

            // child-first, fail if not present
            try {
                Class<?> c = findClass(name);
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            } catch (ClassNotFoundException e) {
                throw new ClassNotFoundException("This class must be loaded in child classloader: " + name, e);
            }
        }
    }

    private static void addAll(Set<URL> target, Enumeration<URL> resources) {
        target.addAll(Collections.list(resources));
    }
}
