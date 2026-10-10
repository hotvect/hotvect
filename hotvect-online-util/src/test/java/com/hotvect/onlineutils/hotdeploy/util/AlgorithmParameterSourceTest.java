package com.hotvect.onlineutils.hotdeploy.util;

import com.hotvect.api.algodefinition.AlgorithmId;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlgorithmParameterSourceTest {
    private static final AlgorithmId ALGORITHM_ID = new AlgorithmId("algo", "1.0.0");
    private static final String METADATA = """
            {"algorithm_name":"algo","algorithm_version":"1.0.0"}
            """;

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void readsPlainAndMixedLegacyLayoutsWithFullRelativeNames(boolean zipped, boolean legacy) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("algo/algorithm-parameters.json", METADATA);
        files.put("algo/nested/left/model.parameter", "left");
        files.put((legacy ? "algo@1.0.0" : "algo") + "/model.parameter", "model");
        files.put((legacy ? "algo@0.5.0" : "algo") + "/nested/right/model.parameter", "right");
        files.put((legacy ? "algo@preview_2-rc.1" : "algo") + "/unversioned.parameter", "unversioned");
        files.put((legacy ? "algo@99.0.0" : "algo") + "/future.parameter", "future");

        try (var source = openSource(zipped, files)) {
            assertEquals(METADATA, new String(source.readMetadata(ALGORITHM_ID).readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(Map.of(
                    "algorithm-parameters.json", METADATA,
                    "nested/left/model.parameter", "left",
                    "model.parameter", "model",
                    "nested/right/model.parameter", "right",
                    "unversioned.parameter", "unversioned",
                    "future.parameter", "future"
            ), parameterContents(source, ALGORITHM_ID));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void treatsAlgorithmNamesLiterally(boolean zipped) throws Exception {
        AlgorithmId algorithmId = new AlgorithmId("algo.[test]+(v1)$^", "1.0.0");
        Map<String, String> files = Map.of(
                algorithmId.algorithmName() + "/algorithm-parameters.json", METADATA,
                algorithmId.algorithmName() + "@legacy_1.0/nested/model.parameter", "model",
                "algoX[test]+(v1)$^/ignored.parameter", "ignored");

        try (var source = openSource(zipped, files)) {
            assertEquals(METADATA, new String(source.readMetadata(algorithmId).readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(Map.of(
                    "algorithm-parameters.json", METADATA,
                    "nested/model.parameter", "model"
            ), parameterContents(source, algorithmId));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void excludesSimilarPrefixesAndInvalidSuffixes(boolean zipped) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("algo/model.parameter", "model");
        for (String directory : List.of(
                "algo-other", "algorithms", "algo@", "algo@1+2", "algo@1 2",
                "algo@1@2", "algo@\u00e9", "wrapper/algo")) {
            files.put(directory + "/model.parameter", "ignored");
        }
        files.put("model.parameter", "ignored");
        files.put("algo/empty/", "");

        try (var source = openSource(zipped, files)) {
            assertEquals(Map.of("model.parameter", "model"), parameterContents(source, ALGORITHM_ID));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void metadataComesOnlyFromThePlainDirectory(boolean zipped) throws Exception {
        try (var source = openSource(zipped, Map.of(
                "algo/algorithm-parameters.json", METADATA,
                "algo@1.0.0/algorithm-parameters.json", "legacy metadata"))) {
            assertEquals(METADATA, new String(source.readMetadata(ALGORITHM_ID).readAllBytes(), StandardCharsets.UTF_8));
            IllegalStateException error = assertThrows(
                    IllegalStateException.class, () -> source.readParameters(ALGORITHM_ID));
            assertTrue(error.getMessage().contains("algorithm-parameters.json"));
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void doesNotFallBackToLegacyMetadata(boolean zipped, boolean plainDirectoryExists) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("algo@1.0.0/algorithm-parameters.json", METADATA);
        if (plainDirectoryExists) {
            files.put("algo/model.parameter", "model");
        }

        try (var source = openSource(zipped, files)) {
            assertThrows(IOException.class, () -> source.readMetadata(ALGORITHM_ID));
            Map<String, String> expected = new LinkedHashMap<>();
            expected.put("algorithm-parameters.json", METADATA);
            if (plainDirectoryExists) {
                expected.put("model.parameter", "model");
            }
            assertEquals(expected, parameterContents(source, ALGORITHM_ID));
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void rejectsDuplicateRelativePaths(boolean zipped, boolean bothLegacy) throws Exception {
        try (var source = openSource(zipped, Map.of(
                (bothLegacy ? "algo@0.5.0" : "algo") + "/nested/model.parameter", "first",
                "algo@1.0.0/nested/model.parameter", "second"))) {
            IllegalStateException error = assertThrows(
                    IllegalStateException.class, () -> source.readParameters(ALGORITHM_ID));
            assertTrue(error.getMessage().contains("nested/model.parameter"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyAlgorithmDirectoriesHaveNoParameters(boolean zipped) throws Exception {
        try (var source = openSource(zipped, Map.of("algo/", "", "algo@1.0.0/", ""))) {
            assertTrue(source.readParameters(ALGORITHM_ID).isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void preservesNoMatchingEntriesBehavior(boolean zipped, boolean empty) throws Exception {
        Map<String, String> files = empty ? Map.of() : Map.of(
                "other/model.parameter", "other",
                "algo@/model.parameter", "invalid suffix",
                "algo", "not a directory");
        try (var source = openSource(zipped, files)) {
            if (zipped) {
                assertTrue(source.readParameters(ALGORITHM_ID).isEmpty());
            } else {
                assertThrows(IOException.class, () -> source.readParameters(ALGORITHM_ID));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directoryFilesAreOpenedOnlyWhenUsed(boolean legacy) throws Exception {
        Path algorithmDirectory = Files.createDirectory(tempDir.resolve(legacy ? "algo@1.0.0" : "algo"));
        Path model = Files.writeString(algorithmDirectory.resolve("model.parameter"), "original");
        Path unused = Files.writeString(algorithmDirectory.resolve("unused.parameter"), "unused");

        try (var source = AlgorithmParameterSource.open(tempDir.toFile())) {
            var parameters = source.readParameters(ALGORITHM_ID);
            Files.move(model, tempDir.resolve("original.parameter"));
            Files.writeString(model, "replacement");
            Files.delete(unused);

            assertEquals("replacement", new String(parameters.get("model.parameter").readAllBytes(), StandardCharsets.UTF_8));
            // Closing the source must not try to open the unused, deleted file.
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingSourceClosesBothOpenedAndUnusedStreams(boolean legacy) throws Exception {
        Path algorithmDirectory = Files.createDirectory(tempDir.resolve(legacy ? "algo@1.0.0" : "algo"));
        Files.writeString(algorithmDirectory.resolve("model.parameter"), "model");
        Files.writeString(algorithmDirectory.resolve("unused.parameter"), "unused");

        try (var source = AlgorithmParameterSource.open(tempDir.toFile())) {
            var parameters = source.readParameters(ALGORITHM_ID);
            var model = parameters.get("model.parameter");
            var unused = parameters.get("unused.parameter");
            assertEquals('m', model.read());

            source.close();

            assertThrows(IOException.class, model::read);
            assertThrows(IOException.class, unused::read);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingAnUnusedStreamDoesNotAllowItToOpenLater(boolean legacy) throws Exception {
        Path algorithmDirectory = Files.createDirectory(tempDir.resolve(legacy ? "algo@1.0.0" : "algo"));
        Files.writeString(algorithmDirectory.resolve("model.parameter"), "model");

        try (var source = AlgorithmParameterSource.open(tempDir.toFile())) {
            var model = source.readParameters(ALGORITHM_ID).get("model.parameter");
            model.close();

            assertThrows(IOException.class, model::read);
        }
    }

    private AlgorithmParameterSource openSource(boolean zipped, Map<String, String> files) throws IOException {
        if (zipped) {
            Path zipPath = tempDir.resolve("parameters.zip");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zipPath))) {
                for (var entry : files.entrySet()) {
                    out.putNextEntry(new ZipEntry(entry.getKey()));
                    out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    out.closeEntry();
                }
            }
            return AlgorithmParameterSource.open(zipPath.toFile());
        }

        Path directory = Files.createDirectory(tempDir.resolve("parameters"));
        for (var entry : files.entrySet()) {
            Path file = directory.resolve(entry.getKey());
            if (entry.getKey().endsWith("/")) {
                Files.createDirectories(file);
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            }
        }
        return AlgorithmParameterSource.open(directory.toFile());
    }

    private static Map<String, String> parameterContents(AlgorithmParameterSource source, AlgorithmId algorithmId)
            throws IOException {
        Map<String, String> contents = new LinkedHashMap<>();
        for (var entry : source.readParameters(algorithmId).entrySet()) {
            contents.put(entry.getKey(), new String(entry.getValue().readAllBytes(), StandardCharsets.UTF_8));
        }
        return contents;
    }
}
