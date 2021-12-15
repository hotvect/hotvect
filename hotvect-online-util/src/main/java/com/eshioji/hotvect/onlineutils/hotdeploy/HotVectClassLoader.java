package com.eshioji.hotvect.onlineutils.hotdeploy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;

import static com.google.common.base.Preconditions.checkNotNull;

/*
 * Code adapted from https://stackoverflow.com/a/5446671 under the CC BY-SA 3.0 license
 * Original author: karoberts
 * Modified by: Enno Shioji
 */

public class HotVectClassLoader extends URLClassLoader{
    public HotVectClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    public HotVectClassLoader(URL[] urls) {
        super(urls);
    }
}

//        ClassLoader {
//    private ChildFirstURLClassLoader childClassLoader;
//
//    public HotVectClassLoader(URL classpath) {
//        super(Thread.currentThread().getContextClassLoader());
//        checkNotNull(classpath);
//
//        URL[] urls = new URL[]{classpath};
//
//        childClassLoader = new ChildFirstURLClassLoader(urls, new ParentClassLoader(this.getParent()));
//    }
//
//    @Override
//    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
//        // If it's already loaded in the parent class loader, use that
//        Class<?> loaded =  super.findLoadedClass(name);
//        if (loaded != null){
//            return loaded;
//        }
//
//        try {
//            // If available, use parents' classes
//            return super.loadClass(name, resolve);
//        } catch (ClassNotFoundException e) {
//            // Was not available in parents, now use child
//            return childClassLoader.loadClass(name, resolve);
//        }
//    }
//
//    @Override
//    public URL getResource(String name) {
//        URL url = this.childClassLoader.getResource(name);
//        if (url == null){
//            return super.getResource(name);
//        } else {
//            return url;
//        }
//    }
//
//    @Override
//    public Enumeration<URL> getResources(String name) throws IOException {
//        Enumeration<URL> urls = this.childClassLoader.getResources(name);
//        if (urls == null){
//            return super.getResources(name);
//        } else {
//            return urls;
//        }
//    }
//
//    @Override
//    public InputStream getResourceAsStream(String name) {
//        InputStream is = this.childClassLoader.getResourceAsStream(name);
//        if (is == null){
//            return super.getResourceAsStream(name);
//        } else {
//            return is;
//        }
//    }
//}
