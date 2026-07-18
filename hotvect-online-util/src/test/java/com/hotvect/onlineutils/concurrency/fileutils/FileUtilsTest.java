package com.hotvect.onlineutils.concurrency.fileutils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void listFilesIncludesExtensionlessSparkTextPartFiles() throws IOException {
        Path dtDir = Files.createDirectories(tempDir.resolve("train").resolve("dt=2025-08-08"));
        Files.writeString(dtDir.resolve("part-00000"), "{\"example_id\":\"a\"}\n");
        writeGzip(dtDir.resolve("part-00001.gz"), "{\"example_id\":\"b\"}\n");
        Files.writeString(dtDir.resolve("part-"), "ignored");
        writeGzip(dtDir.resolve("part-.gz"), "ignored");
        Files.writeString(dtDir.resolve("part-00002.parquet"), "ignored");
        writeGzip(dtDir.resolve("part-00003.parquet.gz"), "ignored");
        writeGzip(dtDir.resolve("part-00004.json.gz"), "{\"example_id\":\"c\"}\n");
        Files.writeString(dtDir.resolve("_SUCCESS"), "");

        List<String> actual = FileUtils.listFiles(List.of(tempDir.toFile()))
                .map(file -> tempDir.relativize(file.toPath()).toString())
                .toList();

        assertEquals(
                List.of(
                        "train/dt=2025-08-08/part-00000",
                        "train/dt=2025-08-08/part-00001.gz",
                        "train/dt=2025-08-08/part-00004.json.gz"
                ),
                actual
        );
    }

    @Test
    void listFilesHandlesLargeShardDirectoriesWithoutRecursiveStreamOverflow() throws IOException {
        Path dtDir = Files.createDirectories(tempDir.resolve("train").resolve("dt=2025-08-08"));
        int fileCount = 12000;
        for (int i = 0; i < fileCount; i++) {
            Files.createFile(dtDir.resolve(String.format("part-%05d", i)));
        }

        long actual = FileUtils.listFiles(List.of(tempDir.toFile())).count();

        assertEquals(fileCount, actual);
    }

    @Test
    void listFilesPreservesTopLevelSourceOrder() throws IOException {
        Path zSource = Files.createDirectories(tempDir.resolve("z-source").resolve("dt=2025-08-08"));
        Path aSource = Files.createDirectories(tempDir.resolve("a-source").resolve("dt=2025-08-08"));
        Files.writeString(zSource.resolve("part-00000"), "{\"example_id\":\"z\"}\n");
        Files.writeString(aSource.resolve("part-00000"), "{\"example_id\":\"a\"}\n");

        List<String> actual = FileUtils.listFiles(List.of(
                        zSource.getParent().toFile(),
                        aSource.getParent().toFile()
                ))
                .map(file -> tempDir.relativize(file.toPath()).toString())
                .toList();

        assertEquals(
                List.of(
                        "z-source/dt=2025-08-08/part-00000",
                        "a-source/dt=2025-08-08/part-00000"
                ),
                actual
        );
    }

    private static void writeGzip(Path path, String content) throws IOException {
        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(path))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
