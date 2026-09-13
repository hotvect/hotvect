package com.hotvect.onlineutils.experimentmanagement.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
import com.hotvect.api.algodefinition.ranking.SimpleRankerFactory;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.onlineutils.experimentmanagement.algodownload.ArtifactUriAlgorithmDownloadClient;
import com.hotvect.onlineutils.experimentmanagement.generated.SlotActiveInfo;
import com.hotvect.onlineutils.serving.EmsPredictionRuntime;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmsSnapshotExporterTest {
    private static final Instant CAPTURE_TIME = Instant.parse("2026-08-14T10:15:30Z");

    @TempDir
    Path tempDir;

    @Test
    void capturesNestedSlotsWithTheSameGraphPreparationRulesAsPrediction() throws Exception {
        Path rootJar = writeAlgorithmJar(
                "root.jar",
                "root",
                RootFactory.class,
                "\"dependencies\": {\"policy-slot\": {\"scope\": \"slot\"}}");
        Path childJar = writeAlgorithmJar("child.jar", "child", ChildFactory.class, null);
        RecordingClient client = new RecordingClient(Map.of(
                "product-ranking", activeInfo("root-salt", "root", rootJar),
                "policy-slot", activeInfo("policy-salt", "child", childJar)));
        Path output = tempDir.resolve("ems-snapshot.json");

        EmsSnapshotExporter exporter = new EmsSnapshotExporter(
                client,
                new ArtifactUriAlgorithmDownloadClient(),
                getClass().getClassLoader(),
                new SimpleMeterRegistry(),
                Clock.fixed(CAPTURE_TIME, ZoneOffset.UTC));
        EmsStateSnapshotDocument document = exporter.export("product-ranking", output);

        assertTrue(Files.isRegularFile(output));
        assertEquals(List.of("policy-slot", "product-ranking"), document.slots().keySet().stream().toList());
        assertEquals("https://ems.example", document.provenance().sourceUri());
        assertEquals("product-ranking", document.provenance().requestedRootSlot());
        assertEquals(CAPTURE_TIME, document.provenance().captureStartedAt());
        assertEquals(CAPTURE_TIME, document.provenance().captureCompletedAt());
        assertEquals(
                EmsSnapshotExporter.READ_CONSISTENCY,
                document.provenance().readConsistency());
        assertEquals(
                Map.of("policy-slot", CAPTURE_TIME, "product-ranking", CAPTURE_TIME),
                document.provenance().slotCapturedAt());
        assertEquals(1, client.requests().get("product-ranking"));
        assertEquals(1, client.requests().get("policy-slot"));
        assertTrue(client.closed());

        FileExperimentManagementStateSource state = new FileExperimentManagementStateSource(output);
        try (EmsPredictionRuntime replay = EmsPredictionRuntime.builder()
                .rootSlot("product-ranking")
                .stateSource(state)
                .downloadClient(new ArtifactUriAlgorithmDownloadClient())
                .scratchDirectory(tempDir.resolve("replay-scratch"))
                .algorithmParentClassLoader(getClass().getClassLoader())
                .meterRegistry(new SimpleMeterRegistry())
                .build()) {
            assertEquals("root-salt", state.getDefaultVariantAndActiveExperiments("product-ranking").slotSalt());
            assertEquals("policy-salt", state.getDefaultVariantAndActiveExperiments("policy-slot").slotSalt());
            assertEquals("root", replay.rootDefinitions().getFirst().algorithmId().algorithmName());
        }
    }

    @Test
    void leavesNoCompletedOutputWhenArtifactInspectionFails() throws Exception {
        RecordingClient client = new RecordingClient(Map.of(
                "product-ranking",
                activeInfo("root-salt", "root", tempDir.resolve("missing-root.jar"))));
        Path output = tempDir.resolve("ems-snapshot.json");
        EmsSnapshotExporter exporter = new EmsSnapshotExporter(
                client,
                new ArtifactUriAlgorithmDownloadClient(),
                getClass().getClassLoader(),
                new SimpleMeterRegistry(),
                Clock.fixed(CAPTURE_TIME, ZoneOffset.UTC));

        assertThrows(RuntimeException.class, () -> exporter.export("product-ranking", output));

        assertFalse(Files.exists(output));
        assertTrue(client.closed());
    }

    @Test
    void createsMissingSnapshotOutputDirectories() throws Exception {
        Path output = tempDir.resolve("nested/snapshots/ems-snapshot.json");
        EmsStateSnapshotDocument document = new EmsStateSnapshotDocument(Map.of(), null);

        EmsStateSnapshotDocument.writeAtomically(output, document);

        assertTrue(Files.isRegularFile(output));
        assertEquals(document, EmsStateSnapshotDocument.read(output));
    }

    private SlotActiveInfo activeInfo(String salt, String algorithmName, Path jar) throws IOException {
        return ExperimentManagementServiceClient.decodeSlotStateDocument(
                """
                {
                  "slot_salt": "%1$s",
                  "total_number_of_shards": 1,
                  "default_variant": {
                    "variant_id": 1,
                    "algorithm": {
                      "algorithm_name": "%2$s",
                      "algorithm_version": "1",
                      "absolute_s3_algorithm_jar_path": "%3$s"
                    },
                    "created_at": "2026-08-14T10:15:30Z",
                    "is_default": true
                  },
                  "experiments": [],
                  "user_forced_assignments": []
                }
                """.formatted(salt, algorithmName, jar.toUri()));
    }

    private Path writeAlgorithmJar(String fileName, String algorithmName, Class<?> factory, String extraDefinition)
            throws IOException {
        Path jar = tempDir.resolve(fileName);
        String suffix = extraDefinition == null ? "" : "," + extraDefinition;
        String definition = """
                {
                  "algorithm_name": "%s",
                  "algorithm_version": "1",
                  "algorithm_factory_classname": "%s"%s
                }
                """.formatted(algorithmName, factory.getName(), suffix);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(algorithmName + "-algorithm-definition.json"));
            output.write(definition.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    private static final class RecordingClient extends ExperimentManagementServiceClient {
        private final Map<String, SlotActiveInfo> documents;
        private final Map<String, Integer> requests = new LinkedHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        private RecordingClient(Map<String, SlotActiveInfo> documents) {
            super(
                    URI.create("https://ems.example"),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    () -> "unused");
            this.documents = Map.copyOf(documents);
        }

        @Override
        public SlotActiveInfo getDefaultVariantAndActiveExperimentsDocument(String slotName) {
            requests.merge(slotName, 1, Integer::sum);
            SlotActiveInfo document = documents.get(slotName);
            if (document == null) {
                throw new IllegalArgumentException("Unexpected EMS slot: " + slotName);
            }
            return document;
        }

        @Override
        public void close() {
            closed.set(true);
        }

        private Map<String, Integer> requests() {
            return Map.copyOf(requests);
        }

        private boolean closed() {
            return closed.get();
        }
    }

    public static final class RootFactory implements CompositeAlgorithmFactory<Ranker<String, String>> {
        @Override
        public Ranker<String, String> create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<JsonNode> hyperparameters,
                Map<String, InputStream> parameters,
                AlgorithmDependencies dependencies) {
            assertTrue(localStateStorage.isPresent());
            return request -> RankingResponse.newResponse(List.of());
        }
    }

    public static final class ChildFactory implements SimpleRankerFactory<String, String> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<String, String> apply(Optional<JsonNode> hyperparameter) {
            return request -> RankingResponse.newResponse(List.of());
        }
    }
}
