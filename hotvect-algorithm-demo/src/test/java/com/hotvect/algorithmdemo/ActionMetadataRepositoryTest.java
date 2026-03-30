package com.hotvect.algorithmdemo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ActionMetadataRepositoryTest {
    @Test
    void loadsJsonlJsonAndPartFilesAndIgnoresSparkMarkers() throws Exception {
        Path examplesDir = Files.createTempDirectory("examples-test");
        Files.writeString(
                examplesDir.resolve("example.json"),
                "{\"example_id\":\"x\",\"foo\":1}\n",
                StandardCharsets.UTF_8
        );

        Path dir = Files.createTempDirectory("action-metadata-test");

        Files.writeString(
                dir.resolve("_SUCCESS"),
                "not json",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                dir.resolve("._ignored"),
                "{\"action_id\":\"x\",\"action_name\":\"x\",\"action_image_url\":\"x\"}\n",
                StandardCharsets.UTF_8
        );

        Files.writeString(
                dir.resolve("metadata.jsonl"),
                """
                        {"action_id":"a","action_name":"A","action_image_url":"https://example/a","x":1}
                        {"action_id":"b","action_name":"B","action_image_url":"https://example/b"}
                        """,
                StandardCharsets.UTF_8
        );

        Files.writeString(
                dir.resolve("part-00000"),
                "{\"action_id\":\"c\",\"action_name\":\"C\",\"action_image_url\":\"https://example/c\"}\n",
                StandardCharsets.UTF_8
        );

        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(dir.resolve("part-00001.json.gz")))) {
            out.write("{\"action_id\":\"d\",\"action_name\":\"D\",\"action_image_url\":\"https://example/d\"}\n".getBytes(StandardCharsets.UTF_8));
        }

        Path dbPath = Files.createTempFile("demo-ui", ".db");
        Files.delete(dbPath);
        Options opts = new Options();
        opts.sourcePath = examplesDir.toFile();
        opts.actionMetadataPath = dir.toFile();
        opts.demoSqlitePath = dbPath.toFile();

        try (DemoSqliteCache cache = DemoSqliteCache.openOrBuild(opts)) {
            ActionMetadataRepository repo = cache.actionMetadata();
            assertEquals(4, repo.size());
            assertEquals("A", repo.requireIfEnabled("a").actionName());
            assertEquals("https://example/d", repo.requireIfEnabled("d").actionImageUrl());
            assertEquals(1, repo.requireJsonIfEnabled("a").get("x").asInt());
        }
    }

    @Test
    void rejectsDuplicateActionId() throws IOException {
        Path examplesDir = Files.createTempDirectory("examples-test-dupe");
        Files.writeString(
                examplesDir.resolve("example.json"),
                "{\"example_id\":\"x\",\"foo\":1}\n",
                StandardCharsets.UTF_8
        );

        Path dir = Files.createTempDirectory("action-metadata-test-dupe");

        Files.writeString(
                dir.resolve("part-00000"),
                """
                        {"action_id":"a","action_name":"A","action_image_url":"https://example/a"}
                        {"action_id":"a","action_name":"A2","action_image_url":"https://example/a2"}
                        """,
                StandardCharsets.UTF_8
        );

        Path dbPath = Files.createTempFile("demo-ui", ".db");
        Files.delete(dbPath);
        Options opts = new Options();
        opts.sourcePath = examplesDir.toFile();
        opts.actionMetadataPath = dir.toFile();
        opts.demoSqlitePath = dbPath.toFile();

        assertThrows(ContractViolationException.class, () -> DemoSqliteCache.openOrBuild(opts));
    }

    @Test
    void getAllIfEnabledAllowsMissingIds() throws Exception {
        Path examplesDir = Files.createTempDirectory("examples-test-partial");
        Files.writeString(
                examplesDir.resolve("example.json"),
                "{\"example_id\":\"x\",\"foo\":1}\n",
                StandardCharsets.UTF_8
        );

        Path dir = Files.createTempDirectory("action-metadata-test-partial");
        Files.writeString(
                dir.resolve("metadata.jsonl"),
                "{\"action_id\":\"a\",\"action_name\":\"A\",\"action_image_url\":\"https://example/a\"}\n",
                StandardCharsets.UTF_8
        );

        Path dbPath = Files.createTempFile("demo-ui", ".db");
        Files.delete(dbPath);
        Options opts = new Options();
        opts.sourcePath = examplesDir.toFile();
        opts.actionMetadataPath = dir.toFile();
        opts.demoSqlitePath = dbPath.toFile();

        try (DemoSqliteCache cache = DemoSqliteCache.openOrBuild(opts)) {
            ActionMetadataRepository repo = cache.actionMetadata();
            Map<String, ActionMetadataRepository.ActionMetadata> found = repo.getAllIfEnabled(java.util.List.of("a", "missing"));
            assertEquals(1, found.size());
            assertEquals("A", found.get("a").actionName());
        }
    }
}
