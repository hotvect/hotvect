package com.hotvect.onlineutils.concurrency.fileutils;

import com.google.common.io.Files;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPInputStream;

public class FileUtils {
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

    private static List<File> sortedEligibleChildren(File source) {
        return Arrays.stream(Objects.requireNonNull(source.listFiles(pathname -> {
            if (pathname.isDirectory()) {
                return true;
            }
            return FileFormat.isSupportedFileName(pathname.getName());
        }))).sorted().collect(Collectors.toList());
    }

    private static List<File> flattenFiles(List<File> sources) {
        Deque<File> stack = new ArrayDeque<>();
        for (int i = sources.size() - 1; i >= 0; i--) {
            stack.push(sources.get(i));
        }

        List<File> flattened = new java.util.ArrayList<>();
        while (!stack.isEmpty()) {
            File source = stack.pop();
            if (source.isDirectory()) {
                List<File> children = sortedEligibleChildren(source);
                for (int i = children.size() - 1; i >= 0; i--) {
                    stack.push(children.get(i));
                }
            } else {
                flattened.add(source);
            }
        }

        return flattened;
    }

    public static <T> Stream<T> readData(List<File> sources) {
        List<File> files = flattenFiles(sources);
        if (files.isEmpty()) {
            return Stream.empty();
        }

        FileFormat.validateUniformFormat(files);
        MultiFileRecordIterator<T> iterator = new MultiFileRecordIterator<>(files);
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false)
                .onClose(iterator::close);
    }

    public static Stream<File> listFiles(List<File> sources) {
        return flattenFiles(sources).stream();
    }

    private static final class MultiFileRecordIterator<T> implements Iterator<T>, AutoCloseable {
        private final List<File> files;
        private int fileIndex = 0;
        private RecordReader<T> currentReader;

        private MultiFileRecordIterator(List<File> files) {
            this.files = files;
        }

        @Override
        public boolean hasNext() {
            while (true) {
                if (currentReader != null && currentReader.hasNext()) {
                    return true;
                }

                closeCurrentReader();
                if (fileIndex >= files.size()) {
                    return false;
                }

                currentReader = RecordReader.create(files.get(fileIndex++));
            }
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return currentReader.next();
        }

        @Override
        public void close() {
            closeCurrentReader();
        }

        private void closeCurrentReader() {
            if (currentReader != null) {
                try {
                    currentReader.close();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    currentReader = null;
                }
            }
        }
    }
}
