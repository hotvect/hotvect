package com.eshioji.hotvect.hotdeploy;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;

public class ChildFirstCloseableClassloader extends ClassLoader implements Closeable {
    private final ChildURLClassLoader childClassloader;

    public ChildFirstCloseableClassloader(List<URL> classpath) {
        super(Thread.currentThread().getContextClassLoader());
        var urls = classpath.toArray(new URL[0]);
        childClassloader = new ChildURLClassLoader(urls, this.getParent());
    }

    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        try {
            // Try the child classloader first
            return childClassloader.findClass(name);
        } catch (ClassNotFoundException e) {
            // Fallback to parent
            return super.loadClass(name, resolve);
        }
    }

    @Override
    public URL getResource(String name) {
        return this.childClassloader.getResource(name);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return this.childClassloader.getResourceAsStream(name);
    }

    @Override
    public void close() throws IOException {
        childClassloader.close();
    }

    private static class ChildURLClassLoader extends CloseableUrlClassloader {
        private final ClassLoader parent;

        public ChildURLClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, null);
            this.parent = parent;
        }

        @Override
        public Class<?> findClass(String name) throws ClassNotFoundException {
            Class<?> loaded = super.findLoadedClass(name);
            if (loaded != null) return loaded;
            try {
                // Try the child first
                return super.findClass(name);
            } catch (ClassNotFoundException e) {
                // Fallback
                return parent.loadClass(name);
            }
        }
    }


}