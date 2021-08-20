package com.eshioji.hotvect.hotdeploy;

import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collection;
import java.util.jar.JarFile;

public class CloseableUrlClassloader extends URLClassLoader {

    public CloseableUrlClassloader(URL[] urls) {
        super(urls);
    }

    public CloseableUrlClassloader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    public void close() {
        try {
            super.close();
            var clazz = URLClassLoader.class;
            var ucp = clazz.getDeclaredField("ucp");
            ucp.setAccessible(true);
            var sunMiscURLClassPath = ucp.get(this);
            var loaders = sunMiscURLClassPath.getClass().getDeclaredField("loaders");
            loaders.setAccessible(true);
            var collection = loaders.get(sunMiscURLClassPath);
            for (var sunMiscURLClassPathJarLoader : ((Collection) collection).toArray()) {
                try {
                    var loader = sunMiscURLClassPathJarLoader.getClass().getDeclaredField("jar");
                    loader.setAccessible(true);
                    var jarFile = loader.get(sunMiscURLClassPathJarLoader);
                    ((JarFile) jarFile).close();
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }

            for (var url : this.getURLs()) {
                if (url.getProtocol().equals("jar")) {
                    ((JarURLConnection) url.openConnection()).getJarFile().close();
                }
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
