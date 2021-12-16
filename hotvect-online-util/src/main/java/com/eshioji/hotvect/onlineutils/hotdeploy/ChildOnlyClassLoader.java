package com.eshioji.hotvect.onlineutils.hotdeploy;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * This code was adapted from https://github.com/apache/spark/blob/b61dce23d2ee7ca95770bc7c390029aae8c65f7e/core/src/main/java/org/apache/spark/util/ChildFirstURLClassLoader.java
 * Under the Apache v2.0 license.
 */

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;

/**
 * A class loader that exclusively reads from its own URL
 * when loading classes and resources.
 */
class ChildOnlyClassLoader extends URLClassLoader {

    public ChildOnlyClassLoader(URL[] urls) {
        super(urls, null);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        return super.findClass(name);
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        return super.loadClass(name, resolve);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        ArrayList<URL> urls = Collections.list(super.getResources(name));
        return Collections.enumeration(urls);
    }

    @Override
    public URL getResource(String name) {
        return super.getResource(name);
    }
}