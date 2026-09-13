package com.hotvect.onlineutils.experimentmanagement.algodownload;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** One downloader-owned staged parameter archive. */
public final class DownloadedAlgorithmParameters implements AutoCloseable {
    private final Path file;

    DownloadedAlgorithmParameters(Path file) {
        this.file = Objects.requireNonNull(file, "file must not be null").toAbsolutePath();
    }

    public File file() {
        return file.toFile();
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(file);
    }
}
