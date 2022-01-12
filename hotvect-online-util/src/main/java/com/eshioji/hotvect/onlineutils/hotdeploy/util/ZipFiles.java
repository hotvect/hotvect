package com.eshioji.hotvect.onlineutils.hotdeploy.util;

import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.google.common.base.Preconditions.checkState;

public class ZipFiles {
    private ZipFiles(){}
    public static Map<String, InputStream> parameters(ZipFile zip) throws IOException {
        ImmutableMap.Builder<String, InputStream> ret = ImmutableMap.builder();
        for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
            ZipEntry entry = e.nextElement();
            checkState(!entry.isDirectory());
            ret.put(entry.getName(), zip.getInputStream(entry));
        }
        return ret.build();
    }

}
