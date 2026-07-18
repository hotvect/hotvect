package com.hotvect.onlineutils.hotdeploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hotvect.api.algodefinition.AlgorithmId;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryLocalStateStorageTest {
    @TempDir
    Path tempDir;

    @Test
    void allocatesExistingEmptyPrivateDirectories() throws Exception {
        Path root = tempDir.resolve("states");
        DirectoryLocalStateStorage storage = storage(root);

        Path first = storage.allocateDirectory();
        Path second = storage.allocateDirectory();

        assertNotEquals(first, second);
        assertEquals(root.toAbsolutePath(), first.getParent());
        assertEquals(root.toAbsolutePath(), second.getParent());
        assertTrue(first.getFileName().toString().startsWith("article-topk-42.3-state-"));
        assertTrue(second.getFileName().toString().startsWith("article-topk-42.3-state-"));
        assertTrue(Files.isDirectory(first));
        assertTrue(Files.isDirectory(second));
        try (var children = Files.list(first)) {
            assertEquals(0, children.count());
        }
        try (var children = Files.list(second)) {
            assertEquals(0, children.count());
        }
    }

    @Test
    void concurrentAllocationsAreUnique() throws Exception {
        Path root = tempDir.resolve("states");
        DirectoryLocalStateStorage storage = storage(root);
        List<Callable<Path>> allocations = java.util.stream.IntStream.range(0, 32)
                .mapToObj(ignored -> (Callable<Path>) storage::allocateDirectory)
                .toList();

        List<Path> paths;
        try (var executor = Executors.newFixedThreadPool(8)) {
            paths = executor.invokeAll(allocations).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).toList();
        }

        assertEquals(paths.size(), paths.stream().distinct().count());
        assertTrue(paths.stream().allMatch(Files::isDirectory));
    }

    @Test
    void allocationFailsWhenRootCannotBeCreated() throws Exception {
        Path root = tempDir.resolve("states");
        Files.writeString(root, "not a directory");

        assertThrows(UncheckedIOException.class, () -> storage(root).allocateDirectory());
    }

    @Test
    void sanitizesAlgorithmIdentityInDirectoryName() {
        Path directory = new DirectoryLocalStateStorage(
                        tempDir.resolve("states"),
                        new AlgorithmId("article/top k", "42.3+live"))
                .allocateDirectory();

        assertTrue(directory.getFileName().toString().startsWith("article-top-k-42.3-live-state-"));
    }

    private static DirectoryLocalStateStorage storage(Path root) {
        return new DirectoryLocalStateStorage(root, new AlgorithmId("article-topk", "42.3"));
    }
}
