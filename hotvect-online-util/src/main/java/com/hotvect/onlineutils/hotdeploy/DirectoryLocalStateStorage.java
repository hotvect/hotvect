package com.hotvect.onlineutils.hotdeploy;

import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class DirectoryLocalStateStorage implements LocalStateStorage {
    private final Path root;
    private final String directoryPrefix;

    DirectoryLocalStateStorage(Path root, AlgorithmId algorithmId) {
        this.root = root.toAbsolutePath();
        this.directoryPrefix = "%s-%s-state-".formatted(
                directoryNamePart(algorithmId.algorithmName()),
                directoryNamePart(algorithmId.algorithmVersion()));
    }

    @Override
    public Path allocateDirectory() {
        try {
            Files.createDirectories(root);
            return Files.createTempDirectory(root, directoryPrefix).toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to allocate local state storage below " + root, e);
        }
    }

    private static String directoryNamePart(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]+", "-");
    }
}
