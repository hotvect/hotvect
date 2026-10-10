package com.hotvect.onlineutils.hotdeploy.util;

import com.hotvect.api.algodefinition.AlgorithmId;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Supplies one packaged algorithm's parameters from either a package directory or a ZIP archive.
 *
 * <p>A directory has the same layout as an archive: each algorithm owns a top-level directory whose
 * contents are its parameter files. This lets local callers use files directly while keeping ZIP
 * archives as a transport format.</p>
 */
public abstract class AlgorithmParameterSource implements AutoCloseable {

    public static AlgorithmParameterSource open(File parameterLocation) throws IOException {
        if (parameterLocation == null) {
            throw new IllegalArgumentException("Parameter source is required, but was not supplied");
        }
        if (parameterLocation.isDirectory()) {
            return new DirectorySource(parameterLocation.toPath());
        }
        if (parameterLocation.isFile()) {
            return new ZipSource(parameterLocation);
        }
        throw new IOException("Specified parameter source does not exist or is not a regular file or directory: " + parameterLocation);
    }

    public abstract InputStream readMetadata(AlgorithmId algorithmId) throws IOException;

    public abstract Map<String, InputStream> readParameters(AlgorithmId algorithmId) throws IOException;

    @Override
    public abstract void close() throws IOException;

    private static Pattern algorithmDirectoryPattern(AlgorithmId algorithmId) {
        return Pattern.compile(Pattern.quote(algorithmId.algorithmName()) + "(@[\\w\\-\\.]+)?");
    }

    private static final class ZipSource extends AlgorithmParameterSource {
        private final ZipFile zipFile;

        private ZipSource(File parameterLocation) throws IOException {
            this.zipFile = new ZipFile(parameterLocation);
        }

        @Override
        public InputStream readMetadata(AlgorithmId algorithmId) throws IOException {
            return ZipFiles.readFromZipByName(
                    zipFile,
                    algorithmId.algorithmName() + "/algorithm-parameters.json");
        }

        @Override
        public Map<String, InputStream> readParameters(AlgorithmId algorithmId) throws IOException {
            Pattern directoryPattern = algorithmDirectoryPattern(algorithmId);
            Map<String, ZipEntry> parameterEntries = new LinkedHashMap<>();
            try (var entries = zipFile.stream()) {
                for (ZipEntry entry : entries
                        .filter(x -> !x.isDirectory())
                        .sorted(Comparator.comparing(ZipEntry::getName))
                        .toList()) {
                    String name = entry.getName();
                    int separator = name.indexOf('/');
                    if (separator < 0 || !directoryPattern.matcher(name.substring(0, separator)).matches()) {
                        continue;
                    }
                    String relativeName = name.substring(separator + 1);
                    ZipEntry previous = parameterEntries.putIfAbsent(relativeName, entry);
                    if (previous != null) {
                        throw new IllegalStateException(
                                "Duplicate parameter file " + relativeName + " in " + previous.getName() + " and " + name);
                    }
                }
            }

            Map<String, InputStream> parameters = new LinkedHashMap<>();
            for (var entry : parameterEntries.entrySet()) {
                parameters.put(entry.getKey(), zipFile.getInputStream(entry.getValue()));
            }
            return parameters;
        }

        @Override
        public void close() throws IOException {
            zipFile.close();
        }
    }

    private static final class DirectorySource extends AlgorithmParameterSource {
        private final Path sourceRoot;
        private final List<InputStream> streams = new ArrayList<>();

        private DirectorySource(Path sourceRoot) throws IOException {
            this.sourceRoot = sourceRoot.toRealPath();
        }

        @Override
        public InputStream readMetadata(AlgorithmId algorithmId) throws IOException {
            return createFileStream(algorithmDirectory(algorithmId).resolve("algorithm-parameters.json"));
        }

        @Override
        public Map<String, InputStream> readParameters(AlgorithmId algorithmId) throws IOException {
            Pattern directoryPattern = algorithmDirectoryPattern(algorithmId);
            List<Path> algorithmDirectories;
            try (var paths = Files.list(sourceRoot)) {
                algorithmDirectories = paths
                        .filter(path -> directoryPattern.matcher(path.getFileName().toString()).matches())
                        .filter(Files::isDirectory)
                        .sorted()
                        .toList();
            }
            if (algorithmDirectories.isEmpty()) {
                throw new IOException(
                        "Parameter source " + sourceRoot + " does not contain an algorithm directory for "
                                + algorithmId.algorithmName());
            }

            Map<String, Path> parameterFiles = new LinkedHashMap<>();
            for (Path algorithmDirectory : algorithmDirectories) {
                try (var paths = Files.walk(algorithmDirectory)) {
                    for (Path parameterFile : paths.filter(Files::isRegularFile).sorted().toList()) {
                        String relativeName = algorithmDirectory.relativize(parameterFile).toString().replace(File.separatorChar, '/');
                        Path previous = parameterFiles.putIfAbsent(relativeName, parameterFile);
                        if (previous != null) {
                            throw new IllegalStateException(
                                    "Duplicate parameter file " + relativeName + " in " + previous + " and " + parameterFile);
                        }
                    }
                }
            }

            Map<String, InputStream> parameters = new LinkedHashMap<>();
            for (var entry : parameterFiles.entrySet()) {
                parameters.put(entry.getKey(), createFileStream(entry.getValue()));
            }
            return parameters;
        }

        @Override
        public void close() throws IOException {
            IOException closeFailure = null;
            for (InputStream stream : streams.reversed()) {
                try {
                    stream.close();
                } catch (IOException e) {
                    if (closeFailure == null) {
                        closeFailure = e;
                    } else {
                        closeFailure.addSuppressed(e);
                    }
                }
            }
            streams.clear();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        private Path algorithmDirectory(AlgorithmId algorithmId) throws IOException {
            Path algorithmDirectory = sourceRoot.resolve(algorithmId.algorithmName()).normalize();
            if (!algorithmDirectory.startsWith(sourceRoot) || !Files.isDirectory(algorithmDirectory)) {
                throw new IOException(
                        "Parameter source " + sourceRoot + " does not contain an algorithm directory for "
                                + algorithmId.algorithmName());
            }
            return algorithmDirectory;
        }

        private InputStream createFileStream(Path file) throws IOException {
            if (!Files.isRegularFile(file)) {
                throw new IOException("Parameter source file does not exist: " + file);
            }
            InputStream stream = new LazyFileInputStream(file);
            streams.add(stream);
            return stream;
        }
    }

    private static final class LazyFileInputStream extends InputStream {
        private final Path file;
        private InputStream delegate;
        private boolean closed;

        private LazyFileInputStream(Path file) {
            this.file = file;
        }

        private InputStream delegate() throws IOException {
            if (closed) {
                throw new IOException("Stream is closed");
            }
            if (delegate == null) {
                delegate = Files.newInputStream(file);
            }
            return delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate().read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return delegate().read(buffer, offset, length);
        }

        @Override
        public long skip(long count) throws IOException {
            return delegate().skip(count);
        }

        @Override
        public int available() throws IOException {
            return delegate().available();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (delegate != null) {
                delegate.close();
            }
        }
    }
}
