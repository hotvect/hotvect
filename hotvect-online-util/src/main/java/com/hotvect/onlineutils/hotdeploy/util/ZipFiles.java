package com.hotvect.onlineutils.hotdeploy.util;

import com.google.common.collect.Iterators;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ZipFiles {
    private ZipFiles() {
    }

    public static InputStream readFromZipFirstMatching(ZipFile zipFile, String pattern) throws IOException {
        try {
            ZipEntry ret = Collections.list(zipFile.entries()).stream().filter(x -> x.getName().matches(pattern)).iterator().next();
            return zipFile.getInputStream(ret);
        } catch (NoSuchElementException e){
            throw new IOException("Requested resource matching:" + pattern + " not found in zipfile with content:" + Collections.list(zipFile.entries()), e);
        }
    }


    public static InputStream readFromZipByName(ZipFile zipFile, String name) throws IOException {
        try {
            ZipEntry ret = Iterators.getOnlyElement(Collections.list(zipFile.entries()).stream().filter(x -> name.equals(x.getName())).iterator());
            return zipFile.getInputStream(ret);
        } catch (NoSuchElementException e){
            throw new IOException("Requested resource:" + name + " not found in zipfile with content:" + Collections.list(zipFile.entries()), e);
        }
    }

    public static Map<String, InputStream> readFromZip(ZipFile zipFile) {
        return readFromZip(zipFile, _x -> true);
    }

    public static Map<String, InputStream> readFromZip(ZipFile zipFile, Predicate<ZipEntry> filter) {
        return Collections.list(zipFile.entries()).stream()
                .filter(filter)
                .filter(x -> !x.isDirectory())
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
        // Add capturing group to extract relative path after algorithm prefix
        String capturePattern = pattern.replace("\\/.+", "/(.+)");
        Pattern p = Pattern.compile(capturePattern);

        return Collections.list(zipFile.entries()).stream()
                .filter(x -> x.getName().matches(pattern))
                .filter(x -> !x.isDirectory())
                .collect(Collectors.toMap(
                        // Name - extract relative path from capturing group
                        k -> {
                            Matcher m = p.matcher(k.getName());
                            if (m.matches()) {
                                int groupCount = m.groupCount();
                                if (groupCount > 0) {
                                    return m.group(groupCount);
                                }
                            }
                            return Path.of(k.getName()).getFileName().toString();
                        },
                        // InputStream
                        v -> {
                            try {
                                return zipFile.getInputStream(v);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }));
    }

}
