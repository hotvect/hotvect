package com.hotvect.onlineutils.experimentmanagement.algodownload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactUriAlgorithmDownloadClientTest {
    @TempDir
    Path tempDir;

    @Test
    void copiesFileArtifactsForSyntheticEmsState() throws Exception {
        Path jar = tempDir.resolve("algorithm.jar");
        Path parameter = tempDir.resolve("parameters.zip");
        Files.write(jar, new byte[] {1, 2, 3});
        Files.write(parameter, new byte[] {4, 5, 6});
        AlgorithmMetadata metadata = new AlgorithmMetadata(
                "ranker",
                "1",
                "parameters-1",
                jar.toUri().toString(),
                parameter.toUri().toString());
        Path copiedJar = tempDir.resolve("downloads/copied.jar");
        Path copiedParameter = tempDir.resolve("downloads/copied.zip");

        try (ArtifactUriAlgorithmDownloadClient client = new ArtifactUriAlgorithmDownloadClient()) {
            client.downloadAlgorithmJar(metadata, copiedJar);
            client.downloadAlgorithmParameter(metadata, copiedParameter);
        }

        assertArrayEquals(Files.readAllBytes(jar), Files.readAllBytes(copiedJar));
        assertArrayEquals(Files.readAllBytes(parameter), Files.readAllBytes(copiedParameter));
    }

    @Test
    void rejectsAmbiguousSchemeLessArtifactPaths() {
        AlgorithmMetadata metadata = new AlgorithmMetadata(
                "ranker",
                "1",
                null,
                tempDir.resolve("algorithm.jar").toString(),
                null);

        try (ArtifactUriAlgorithmDownloadClient client = new ArtifactUriAlgorithmDownloadClient()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.downloadAlgorithmJar(metadata, tempDir.resolve("copied.jar")));
        }
    }
}
