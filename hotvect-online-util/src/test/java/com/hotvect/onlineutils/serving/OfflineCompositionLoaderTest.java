package com.hotvect.onlineutils.serving;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmDownloadClient;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineCompositionLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void closesTheOwnedDownloadClientWhenScratchDirectoryCreationFails() throws Exception {
        Path scratch = Files.createFile(tempDir.resolve("not-a-directory"));
        AtomicBoolean clientClosed = new AtomicBoolean();
        AlgorithmDownloadClient downloadClient = new AlgorithmDownloadClient() {
            @Override
            public void downloadAlgorithmJar(AlgorithmMetadata metadata, Path destination) {
                throw new AssertionError("Scratch failure must precede downloading");
            }

            @Override
            public void downloadAlgorithmParameter(AlgorithmMetadata metadata, Path destination) {
                throw new AssertionError("Scratch failure must precede downloading");
            }

            @Override
            public void close() {
                clientClosed.set(true);
            }
        };
        OfflineCompositionLoader.Options options = new OfflineCompositionLoader.Options()
                .downloadClient(downloadClient)
                .scratchDirectory(scratch);
        assertThrows(FileAlreadyExistsException.class, () -> OfflineCompositionLoader.load(
                options, (repository, client, resolveExecutionContext) -> {
                    throw new AssertionError("Scratch failure must precede preparation");
                }));

        assertTrue(clientClosed.get());
    }
}
