package com.hotvect.onlineutils.concurrency.fileutils;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

public class FileUtils {
    private static final Logger log = LoggerFactory.getLogger(FileUtils.class);
    public static final ImmutableSet<String> ALLOWED_LOWERCASE_EXTENSIONS = defineAllowedExtensions();

    private static ImmutableSet<String> defineAllowedExtensions() {
        ImmutableSet.Builder<String> ret = ImmutableSet.builder();

        // Add all extensions from all FileFormat enums
        for (FileFormat format : FileFormat.values()) {
            ret.addAll(format.getExtensions());
        }

        return ret.build();
    }

    public static BufferedReader toBufferedReader(File source){
        try {
            //noinspection UnstableApiUsage
            String ext = Files.getFileExtension(source.toPath().getFileName().toString());
            InputStream file = java.nio.file.Files.newInputStream(source.toPath(), StandardOpenOption.READ);
            InputStream spout = "gz".equalsIgnoreCase(ext) ? new GZIPInputStream(file, 128 << 10) : file;
            return new BufferedReader(new InputStreamReader(spout, StandardCharsets.UTF_8), 128 * 1024);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Stream<String> readLines(File source) {
        return toBufferedReader(source).lines();
    }

    private FileUtils() {
    }

    private static <V> Stream<V> recursiveFlatmap(List<File> sources, Function<File, Stream<V>> mapFun){
        Stream<V> flattened = Stream.empty();

        for (File source : sources) {
            Stream<V> returned = recursiveFlatmap(source, mapFun);
            flattened = Stream.concat(flattened, returned);
        }

        return flattened;
    }

    private static <V> Stream<V> recursiveFlatmap(File source, Function<File, Stream<V>> mapFun) {
        if (source.isDirectory()) {
            List<File> sources = Arrays.stream(Objects.requireNonNull(source.listFiles(pathname -> {
                if (pathname.isDirectory()) {
                    return true;
                }
                for (String allowedLowercaseExtension : ALLOWED_LOWERCASE_EXTENSIONS) {
                    if (pathname.getName().toLowerCase().endsWith(allowedLowercaseExtension)) {
                        return true;
                    }
                }
                return false;
            }))).sorted().collect(Collectors.toList());

            return recursiveFlatmap(sources, mapFun);
        } else {
            return mapFun.apply(source);
        }
    }

    public static Stream<String> readData(List<File> sources) {
        return recursiveFlatmap(sources, FileUtils::readLines);
    }

    public static Stream<File> listFiles(List<File> sources) {
        return recursiveFlatmap(sources, Stream::of);
    }
}
