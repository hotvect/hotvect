package com.hotvect.onlineutils.hotdeploy;

import com.google.common.collect.ImmutableSet;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
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
}
