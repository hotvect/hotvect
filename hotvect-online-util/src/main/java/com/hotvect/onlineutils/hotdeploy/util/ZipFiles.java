package com.hotvect.onlineutils.hotdeploy.util;

import com.google.common.collect.Iterators;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class ZipFiles {
    private ZipFiles() {
    }

    public static InputStream readFromZipFirstMatching(ZipFile zipFile, String pattern) throws IOException {
        try {
            ZipArchiveEntry ret = Collections.list(zipFile.getEntries()).stream().filter(x -> x.getName().matches(pattern)).iterator().next();
            return zipFile.getInputStream(ret);
        } catch (NoSuchElementException e){
            throw new IOException("Requested resource matching:" + pattern + " not found in zipfile with content:" + Collections.list(zipFile.getEntries()), e);
        }
    }


    public static InputStream readFromZipByName(ZipFile zipFile, String name) throws IOException {
        try {
            ZipArchiveEntry ret = Iterators.getOnlyElement(Collections.list(zipFile.getEntries()).stream().filter(x -> name.equals(x.getName())).iterator());
            return zipFile.getInputStream(ret);
        } catch (NoSuchElementException e){
            throw new IOException("Requested resource:" + name + " not found in zipfile with content:" + Collections.list(zipFile.getEntries()), e);
        }
    }

    public static Map<String, InputStream> readFromZip(ZipFile zipFile) {
        return readFromZip(zipFile, _x -> true);
    }

        public static Map<String, InputStream> readFromZip(ZipFile zipFile, Predicate<ZipArchiveEntry> filter) {
        return Collections.list(zipFile.getEntries()).stream()
                .filter(filter)
                .collect(Collectors.toMap(
                        // Name
                        k -> Path.of(k.getName()).getFileName().toString(),
                        // InputStream
                        v -> {
                            try {
                                return zipFile.getInputStream(v);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }));
    }


    public static Map<String, InputStream> readFromZipWithPrefix(ZipFile zipFile, String prefix) {
        return readFromZip(zipFile, x -> x.getName().startsWith(prefix));
    }

    public static Map<String, InputStream> readFromZipMatching(ZipFile zipFile, String pattern) {
        return readFromZip(zipFile, x -> x.getName().matches(pattern));
    }

}
