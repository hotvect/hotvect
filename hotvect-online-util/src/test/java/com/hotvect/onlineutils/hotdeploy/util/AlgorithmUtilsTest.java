package com.hotvect.onlineutils.hotdeploy.util;

import com.hotvect.api.algodefinition.AlgorithmId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AlgorithmUtilsTest {

    @TempDir
    Path tmpDir;

    @Test
    void readAlgorithmParameterMetadata_readsAlgorithmIdFromZipWhenNotStrict() throws Exception {
        AlgorithmId expectedAlgorithmId = new AlgorithmId("my-algo", "82.2.21");
        Path zipPath = tmpDir.resolve("params.zip");

        String paramsJson =
                "{\n" +
                "  \"algorithm_name\": \"my-algo\",\n" +
                "  \"algorithm_version\": \"82.1.0\",\n" +
                "  \"parameter_id\": \"p123\",\n" +
                "  \"ran_at\": \"2026-01-24T00:00:00Z\"\n" +
                "}\n";

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            zos.putNextEntry(new ZipEntry("my-algo/algorithm-parameters.json"));
            zos.write(paramsJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        var md = AlgorithmUtils.readAlgorithmParameterMetadata(expectedAlgorithmId, zipPath.toFile(), false);
        assertEquals("my-algo", md.algorithmId().algorithmName());
        assertEquals("82.1.0", md.algorithmId().algorithmVersion());
        assertEquals("p123", md.parameterId());
    }

    @Test
    void readAlgorithmParameterMetadata_strictCheckFailsOnVersionMismatch() throws Exception {
        AlgorithmId expectedAlgorithmId = new AlgorithmId("my-algo", "82.2.21");
        Path zipPath = tmpDir.resolve("params.zip");

        String paramsJson =
                "{\n" +
                "  \"algorithm_name\": \"my-algo\",\n" +
                "  \"algorithm_version\": \"82.1.0\",\n" +
                "  \"parameter_id\": \"p123\",\n" +
                "  \"ran_at\": \"2026-01-24T00:00:00Z\"\n" +
                "}\n";

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            zos.putNextEntry(new ZipEntry("my-algo/algorithm-parameters.json"));
            zos.write(paramsJson.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        assertThrows(
                IllegalStateException.class,
                () -> AlgorithmUtils.readAlgorithmParameterMetadata(expectedAlgorithmId, zipPath.toFile(), true)
        );
    }
}

