package com.hotvect.onlineutils.hotdeploy.util;

import com.google.common.collect.Iterators;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.NoSuchElementException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ZipFiles {
    private ZipFiles() {
    }

    public static InputStream readFromZipByName(ZipFile zipFile, String name) throws IOException {
        try {
            ZipEntry ret = Iterators.getOnlyElement(Collections.list(zipFile.entries()).stream().filter(x -> name.equals(x.getName())).iterator());
            return zipFile.getInputStream(ret);
        } catch (NoSuchElementException e){
            throw new IOException("Requested resource:" + name + " not found in zipfile with content:" + Collections.list(zipFile.entries()), e);
        }
    }

}
