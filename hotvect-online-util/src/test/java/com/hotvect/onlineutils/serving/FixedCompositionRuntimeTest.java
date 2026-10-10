package com.hotvect.onlineutils.serving;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
import com.hotvect.api.algodefinition.ranking.SimpleRankerFactory;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.topk.TopKRequest;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.onlineutils.experimentmanagement.algodownload.ArtifactUriAlgorithmDownloadClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixedCompositionRuntimeTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void preparesLeafRootAndRejectsInvocationAfterClose() throws Exception {
        Path rootJar = writeJar(
                "root.jar",
                Map.of("root-algorithm-definition.json", definition("root", "1", LeafFactory.class, null)));
        Path composition = writeComposition(
                "root@1",
                Map.of("root@1", Artifact.parameterless(rootJar)),
                Map.of());

        FixedCompositionRuntime runtime = runtime(composition);
        try {
            assertEquals(
                    "root",
                    runtime.invoke(rankingRequest()).additionalProperties().get("tag"));
            assertEquals("root@1", runtime.runtimeId().algorithm().algorithmId().value());
            assertEquals("root@1", runtime.canonicalComposition().path("root").asText());
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.invoke(new TopKRequest<>("example", Instant.EPOCH, "shared", 10)));
        } finally {
            runtime.close();
        }

        assertThrows(
                IllegalStateException.class,
                () -> runtime.invoke(rankingRequest()));
    }

    @Test
    void reusesABorrowedContextAndClosesSynchronouslyAtTaskEnd() throws Exception {
        Path rootJar = writeJar(
                "root.jar",
                Map.of("root-algorithm-definition.json", definition(
                        "root", "1", CloseTrackingRankerFactory.class, null)));
        Path composition = writeComposition(
                "root@1",
                Map.of("root@1", Artifact.parameterless(rootJar)),
                Map.of());
        FixedCompositionRuntime runtime = runtime(composition);
        AlgorithmRuntimeContext context = runtime.context();
        CloseTrackingRanker algorithm = (CloseTrackingRanker) context.algorithmInstance().algorithm();
        try (runtime) {
            assertSame(context, runtime.context());
            algorithm.rank(rankingRequest());
            assertEquals(0, algorithm.closeCalls);
        }

        assertEquals(1, algorithm.closeCalls);
        assertSame(Thread.currentThread(), algorithm.closedOn);
        assertThrows(IllegalStateException.class, runtime::context);
        assertThrows(IllegalStateException.class, context::algorithmInstance);
        runtime.close();
        assertEquals(1, algorithm.closeCalls);
    }

    @Test
    void shutdownSurfacesAlgorithmCleanupFailureAndStillReleasesArtifacts() throws Exception {
        Path rootJar = writeJar("root.jar", Map.of(
                "root-algorithm-definition.json", definition("root", "1", FailingCloseFactory.class, null)));
        Path composition = writeComposition("root@1", Map.of("root@1", Artifact.parameterless(rootJar)), Map.of());
        FixedCompositionRuntime runtime = runtime(composition);
        Path scratch = tempDir.resolve("scratch-1");
        List<Path> stagedJars;
        try (var files = Files.list(scratch)) {
            stagedJars = files.filter(path -> path.toString().endsWith(".jar")).toList();
        }
        assertEquals(1, stagedJars.size());

        IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::close);

        assertEquals("algorithm cleanup failed", failure.getMessage());
        assertTrue(stagedJars.stream().noneMatch(Files::exists));
    }

    @Test
    void preparesParameterizedNestedSlotsAsOneFixedGraph() throws Exception {
        Path rootJar = writeJar(
                "root.jar",
                Map.of(
                        "root-algorithm-definition.json",
                        definition(
                                "root",
                                "1",
                                DependencyFactory.class,
                                "{\"policy-slot\": {\"scope\": \"slot\"}}")));
        Path parameterArchive = writeParameters("root", "1", "root-p1");
        Path policyAJar = writeJar(
                "policy-a.jar",
                Map.of(
                        "policy-a-algorithm-definition.json",
                        definition(
                                "policy-a",
                                "1",
                                DependencyFactory.class,
                                "{\"feature-slot\": {\"scope\": \"slot\"}}")));
        Path encoderJar = writeJar(
                "encoder.jar",
                Map.of("encoder-algorithm-definition.json", definition("encoder", "2", LeafFactory.class, null)));
        Path composition = writeComposition(
                "root@1",
                Map.of(
                        "root@1", Artifact.parameterized(rootJar, parameterArchive),
                        "policy-a@1", Artifact.parameterless(policyAJar),
                        "encoder@2", Artifact.parameterless(encoderJar)),
                Map.of(
                        "policy-slot", "policy-a@1",
                        "feature-slot", "encoder@2"));

        try (FixedCompositionRuntime runtime = runtime(composition)) {
            AlgorithmRuntimeContext context = runtime.context();
            JsonNode configuredParameter = runtime.canonicalComposition()
                    .path("algorithms")
                    .path("root@1")
                    .path("parameter");
            assertFalse(configuredParameter.has("id"));
            assertEquals(parameterArchive.toUri().toString(), configuredParameter.path("uri").asText());
            AlgorithmInstance<?> root = context.algorithmInstance();
            assertEquals("root-p1", root.algorithmParameterMetadata().parameterId());
            AlgorithmInstance<?> policyA = dependencies(root).asMap().get("policy-slot");
            assertEquals("policy-a", policyA.algorithmDefinition().algorithmId().algorithmName());

            AlgorithmInstance<?> encoderA = dependencies(policyA).asMap().get("feature-slot");
            assertEquals("encoder@2", encoderA.algorithmDefinition().algorithmId().value());
        }
    }

    @Test
    void resolvesPrivateAndStaticSharedDependenciesInsideAnArtifact() throws Exception {
        Path rootJar = writeJar(
                "root-with-packaged-dependencies.jar",
                Map.of(
                        "root-algorithm-definition.json",
                        definition(
                                "root",
                                "1",
                                DependencyFactory.class,
                                "{\"private-child\": {}, \"shared-child@1\": {\"scope\": \"shared\"}}"),
                        "private-child-algorithm-definition.json",
                        definition("private-child", "1", LeafFactory.class, null),
                        "shared-child-algorithm-definition.json",
                        definition("shared-child", "1", LeafFactory.class, null)));
        Path composition = writeComposition(
                "root@1",
                Map.of("root@1", Artifact.parameterless(rootJar)),
                Map.of());

        try (FixedCompositionRuntime runtime = runtime(composition)) {
            AlgorithmRuntimeContext context = runtime.context();
            AlgorithmInstance<?> root = context.algorithmInstance();
            assertEquals(
                    List.of("private-child", "shared-child"),
                    dependencies(root).asMap().keySet().stream().toList());
            assertEquals("private-child@1", dependencies(root).asMap().get("private-child")
                    .algorithmDefinition().algorithmId().value());
            assertEquals("shared-child@1", dependencies(root).asMap().get("shared-child")
                    .algorithmDefinition().algorithmId().value());
        }
    }

    @Test
    void appliesSelectedParameterArchiveToEveryAlgorithmPackagedInTheArtifact() throws Exception {
        Path rootJar = writeJar(
                "parameterized-root-with-packaged-dependencies.jar",
                Map.of(
                        "root-algorithm-definition.json",
                        definition(
                                "root",
                                "1",
                                DependencyFactory.class,
                                "{\"private-child\": {}, \"shared-child@1\": {\"scope\": \"shared\"}}"),
                        "private-child-algorithm-definition.json",
                        definition("private-child", "1", ParameterReadingFactory.class, null),
                        "shared-child-algorithm-definition.json",
                        definition("shared-child", "1", ParameterReadingFactory.class, null)));
        Path parameters = writeParameters(
                new ParameterFixture("root", "1", "root-p1", null),
                new ParameterFixture("private-child", "1", "private-p1", "private-model"),
                new ParameterFixture("shared-child", "1", "shared-p1", "shared-model"));
        Path composition = writeComposition(
                "root@1",
                Map.of("root@1", Artifact.parameterized(rootJar, parameters)),
                Map.of());

        try (FixedCompositionRuntime runtime = runtime(composition)) {
            AlgorithmRuntimeContext context = runtime.context();
            AlgorithmInstance<?> root = context.algorithmInstance();
            assertEquals("root-p1", root.algorithmParameterMetadata().parameterId());
            AlgorithmInstance<?> privateChild = dependencies(root).asMap().get("private-child");
            assertEquals("private-p1", privateChild.algorithmParameterMetadata().parameterId());
            assertEquals("private-model", tag(privateChild));
            AlgorithmInstance<?> sharedChild = dependencies(root).asMap().get("shared-child");
            assertEquals("shared-p1", sharedChild.algorithmParameterMetadata().parameterId());
            assertEquals("shared-model", tag(sharedChild));
        }
    }

    @Test
    void rejectsMissingUnusedAndCyclicCompositionConfiguration() throws Exception {
        Path rootWithSlot = writeJar(
                "root-with-slot.jar",
                Map.of(
                        "root-algorithm-definition.json",
                        definition(
                                "root",
                                "1",
                                DependencyFactory.class,
                                "{\"children-slot\": {\"scope\": \"slot\"}}")));
        Path childWithSameSlot = writeJar(
                "child-with-same-slot.jar",
                Map.of(
                        "child-algorithm-definition.json",
                        definition(
                                "child",
                                "1",
                                DependencyFactory.class,
                                "{\"children-slot\": {\"scope\": \"slot\"}}")));
        Path childLeaf = writeJar(
                "child.jar",
                Map.of("child-algorithm-definition.json", definition("child", "1", LeafFactory.class, null)));
        Path leaf = writeJar(
                "unused.jar",
                Map.of("unused-algorithm-definition.json", definition("unused", "1", LeafFactory.class, null)));

        Path missingBinding = writeComposition(
                "root@1",
                Map.of("root@1", Artifact.parameterless(rootWithSlot)),
                Map.of());
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> runtime(missingBinding));
        assertTrue(missing.getMessage().contains("does not bind slot children-slot"));

        Path unusedAlgorithm = writeComposition(
                "root@1",
                Map.of(
                        "root@1", Artifact.parameterless(rootWithSlot),
                        "child@1", Artifact.parameterless(childLeaf),
                        "unused@1", Artifact.parameterless(leaf)),
                Map.of("children-slot", "child@1"));
        IllegalArgumentException unreachableAlgorithm =
                assertThrows(IllegalArgumentException.class, () -> runtime(unusedAlgorithm));
        assertTrue(unreachableAlgorithm.getMessage().contains("not reachable from root"));
        assertTrue(unreachableAlgorithm.getMessage().contains("unused@1"));

        Path unusedBinding = writeComposition(
                "unused@1",
                Map.of("unused@1", Artifact.parameterless(leaf)),
                Map.of("unused-slot", "unused@1"));
        IllegalArgumentException unreachableBinding =
                assertThrows(IllegalArgumentException.class, () -> runtime(unusedBinding));
        assertTrue(unreachableBinding.getMessage().contains("slot bindings are not reachable"));

        Path cycle = writeComposition(
                "root@1",
                Map.of(
                        "root@1", Artifact.parameterless(rootWithSlot),
                        "child@1", Artifact.parameterless(childWithSameSlot)),
                Map.of("children-slot", "child@1"));
        IllegalArgumentException cyclic = assertThrows(IllegalArgumentException.class, () -> runtime(cycle));
        assertTrue(cyclic.getMessage().contains("Cyclic fixed composition slot dependency"));
    }

    @Test
    void rejectsWrongArtifactIdentityAndKeepsStaticSharedDependenciesPrivateOffline() throws Exception {
        Path wrongIdentityJar = writeJar(
                "wrong-identity.jar",
                Map.of("root-algorithm-definition.json", definition("root", "2", LeafFactory.class, null)));
        Path wrongIdentity = writeComposition(
                "root@1",
                Map.of("root@1", Artifact.parameterless(wrongIdentityJar)),
                Map.of());
        IllegalStateException identityFailure = assertThrows(IllegalStateException.class, () -> runtime(wrongIdentity));
        assertTrue(identityFailure.getMessage().contains("JAR contains root@2 but the configured selection is root@1"));

        Path rootJar = writeJar(
                "root-with-slot.jar",
                Map.of(
                        "root-algorithm-definition.json",
                        definition(
                                "root",
                                "1",
                                DependencyFactory.class,
                                "{\"first-slot\": {\"scope\": \"slot\"}, "
                                        + "\"second-slot\": {\"scope\": \"slot\"}}")));
        Path first = writeJar(
                "first.jar",
                Map.of(
                        "first-algorithm-definition.json",
                        definition(
                                "first",
                                "1",
                                DependencyFactory.class,
                                "{\"shared@1\": {\"scope\": \"shared\"}}"),
                        "shared-algorithm-definition.json",
                        definition("shared", "1", SharedFactoryA.class, null)));
        Path second = writeJar(
                "second.jar",
                Map.of(
                        "second-algorithm-definition.json",
                        definition(
                                "second",
                                "1",
                                DependencyFactory.class,
                                "{\"shared@1\": {\"scope\": \"shared\"}}"),
                        "shared-algorithm-definition.json",
                        definition("shared", "1", SharedFactoryB.class, null)));
        Path composition = writeComposition(
                "root@1",
                Map.of(
                        "root@1", Artifact.parameterless(rootJar),
                        "first@1", Artifact.parameterless(first),
                        "second@1", Artifact.parameterless(second)),
                Map.of(
                        "first-slot", "first@1",
                        "second-slot", "second@1"));
        try (FixedCompositionRuntime runtime = runtime(composition)) {
            AlgorithmRuntimeContext context = runtime.context();
            AlgorithmInstance<?> root = context.algorithmInstance();
            AlgorithmInstance<?> firstShared = dependencies(
                    dependencies(root).asMap().get("first-slot")).asMap().get("shared");
            AlgorithmInstance<?> secondShared = dependencies(
                    dependencies(root).asMap().get("second-slot")).asMap().get("shared");
            assertNotSame(firstShared, secondShared);
            assertEquals("shared-a", tag(firstShared));
            assertEquals("shared-b", tag(secondShared));
        }
    }

    @Test
    void rejectsDuplicateCatalogKeysAndPluralSlotBindings() throws Exception {
        Path rootJar = writeJar(
                "root.jar",
                Map.of("root-algorithm-definition.json", definition("root", "1", LeafFactory.class, null)));
        String rootUri = rootJar.toUri().toString();

        Path duplicateCatalogKey = tempDir.resolve("duplicate-catalog.json");
        Files.writeString(
                duplicateCatalogKey,
                """
                {
                  "root": "root@1",
                  "algorithms": {
                    "root@1": {"jar_uri": "%s"},
                    "root@1": {"jar_uri": "%s"}
                  },
                  "slot_bindings": {}
                }
                """.formatted(rootUri, rootUri));
        assertThrows(IOException.class, () -> FixedAlgorithmComposition.read(duplicateCatalogKey));

        Path pluralBinding = tempDir.resolve("plural-binding.json");
        Files.writeString(
                pluralBinding,
                """
                {
                  "root": "root@1",
                  "algorithms": {"root@1": {"jar_uri": "%s"}},
                  "slot_bindings": {"slot": ["root@1"]}
                }
                """.formatted(rootUri));
        assertThrows(IOException.class, () -> FixedAlgorithmComposition.read(pluralBinding));
    }

    @Test
    void rejectsUnknownAndTrailingCompositionContent() throws Exception {
        Path rootJar = writeJar(
                "root.jar",
                Map.of("root-algorithm-definition.json", definition("root", "1", LeafFactory.class, null)));
        String rootUri = rootJar.toUri().toString();

        Path unknownField = tempDir.resolve("unknown-field.json");
        Files.writeString(
                unknownField,
                """
                {
                  "root": "root@1",
                  "algorithms": {"root@1": {"jar_uri": "%s"}},
                  "slot_bindings": {},
                  "unexpected": true
                }
                """.formatted(rootUri));
        assertThrows(IOException.class, () -> FixedAlgorithmComposition.read(unknownField));

        Path trailingDocument = tempDir.resolve("trailing-document.json");
        Files.writeString(
                trailingDocument,
                """
                {"root":"root@1","algorithms":{"root@1":{"jar_uri":"%s"}},"slot_bindings":{}}
                {"not":"part of the composition"}
                """.formatted(rootUri));
        assertThrows(IOException.class, () -> FixedAlgorithmComposition.read(trailingDocument));
    }

    private FixedCompositionRuntime runtime(Path composition) throws Exception {
        return FixedCompositionRuntime.builder()
                .composition(composition)
                .downloadClient(new ArtifactUriAlgorithmDownloadClient())
                .scratchDirectory(Files.createDirectory(tempDir.resolve("scratch-" + scratchSequence.incrementAndGet())))
                .algorithmParentClassLoader(getClass().getClassLoader())
                .build();
    }

    private final AtomicInteger scratchSequence = new AtomicInteger();

    private Path writeComposition(
            String root,
            Map<String, Artifact> algorithms,
            Map<String, String> slotBindings) throws IOException {
        ObjectNode document = OBJECT_MAPPER.createObjectNode();
        document.put("root", root);
        ObjectNode catalog = document.putObject("algorithms");
        algorithms.forEach((id, artifact) -> {
            ObjectNode entry = catalog.putObject(id);
            entry.put("jar_uri", artifact.jar().toUri().toString());
            if (artifact.parameterArchive() != null) {
                ObjectNode parameter = entry.putObject("parameter");
                parameter.put("uri", artifact.parameterArchive().toUri().toString());
            }
        });
        ObjectNode bindings = document.putObject("slot_bindings");
        slotBindings.forEach(bindings::put);
        Path composition = tempDir.resolve("composition-" + compositionSequence.incrementAndGet() + ".json");
        OBJECT_MAPPER.writeValue(composition.toFile(), document);
        return composition;
    }

    private final AtomicInteger compositionSequence = new AtomicInteger();

    private Path writeJar(String name, Map<String, String> definitions) throws IOException {
        Path jar = tempDir.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> definition : definitions.entrySet()) {
                output.putNextEntry(new JarEntry(definition.getKey()));
                output.write(definition.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return jar;
    }

    private Path writeParameters(String algorithmName, String algorithmVersion, String parameterId) throws IOException {
        return writeParameters(new ParameterFixture(algorithmName, algorithmVersion, parameterId, null));
    }

    private Path writeParameters(ParameterFixture... fixtures) throws IOException {
        Path parameters = tempDir.resolve(fixtures[0].algorithmName() + "-" + fixtures[0].parameterId() + ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(parameters))) {
            for (ParameterFixture fixture : fixtures) {
                output.putNextEntry(new ZipEntry(fixture.algorithmName() + "/algorithm-parameters.json"));
                output.write("""
                        {"algorithm_name":"%s","algorithm_version":"%s",\
                        "parameter_id":"%s","ran_at":"%s","last_test_time":"2026-08-01"}
                        """.formatted(
                                fixture.algorithmName(),
                                fixture.algorithmVersion(),
                                fixture.parameterId(),
                                Instant.parse("2026-08-01T00:00:00Z"))
                        .getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
                if (fixture.model() != null) {
                    output.putNextEntry(new ZipEntry(fixture.algorithmName() + "/model.txt"));
                    output.write(fixture.model().getBytes(StandardCharsets.UTF_8));
                    output.closeEntry();
                }
            }
        }
        return parameters;
    }

    private static String definition(String name, String version, Class<?> factory, String dependencies) {
        String dependencyField = dependencies == null ? "" : ",\"dependencies\":" + dependencies;
        return """
                {"algorithm_name":"%s","algorithm_version":"%s",\
                "algorithm_factory_classname":"%s"%s}
                """.formatted(name, version, factory.getName(), dependencyField);
    }

    private static String tag(AlgorithmInstance<?> instance) {
        return ((TaggedRanker) instance.algorithm()).tag();
    }

    private static RankingRequest<String, String> rankingRequest() {
        return RankingRequest.ofAvailableActions("example", "shared", List.of());
    }

    private static AlgorithmDependencies dependencies(AlgorithmInstance<?> instance) {
        return ((TaggedRanker) instance.algorithm()).dependencies();
    }

    private record Artifact(Path jar, Path parameterArchive) {
        private static Artifact parameterless(Path jar) {
            return new Artifact(jar, null);
        }

        private static Artifact parameterized(Path jar, Path parameterArchive) {
            return new Artifact(jar, parameterArchive);
        }
    }

    private record ParameterFixture(
            String algorithmName,
            String algorithmVersion,
            String parameterId,
            String model) {
    }

    public static final class LeafFactory implements SimpleRankerFactory<String, String> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<String, String> apply(Optional<JsonNode> hyperparameter) {
            return new TaggedRanker("root", AlgorithmDependencies.empty());
        }
    }

    public static final class DependencyFactory implements CompositeAlgorithmFactory<Ranker<String, String>> {
        @Override
        public Ranker<String, String> create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<JsonNode> hyperparameters,
                Map<String, InputStream> parameters,
                AlgorithmDependencies dependencies) {
            return new TaggedRanker("composite", dependencies);
        }
    }

    public static final class ParameterReadingFactory implements CompositeAlgorithmFactory<Ranker<String, String>> {
        @Override
        public Ranker<String, String> create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<JsonNode> hyperparameters,
                Map<String, InputStream> parameters,
                AlgorithmDependencies dependencies) {
            try {
                InputStream model = Objects.requireNonNull(parameters.get("model.txt"), "model.txt is required");
                return new TaggedRanker(
                        new String(model.readAllBytes(), StandardCharsets.UTF_8),
                        dependencies);
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        }
    }

    public static final class SharedFactoryA implements SimpleRankerFactory<String, String> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<String, String> apply(Optional<JsonNode> hyperparameter) {
            return new TaggedRanker("shared-a", AlgorithmDependencies.empty());
        }
    }

    public static final class SharedFactoryB implements SimpleRankerFactory<String, String> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<String, String> apply(Optional<JsonNode> hyperparameter) {
            return new TaggedRanker("shared-b", AlgorithmDependencies.empty());
        }
    }

    public static final class FailingCloseFactory implements SimpleRankerFactory<String, String> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<String, String> apply(Optional<JsonNode> hyperparameter) {
            return new Ranker<>() {
                @Override
                public RankingResponse<String> rank(RankingRequest<String, String> request) {
                    return RankingResponse.newResponse(List.of());
                }

                @Override
                public void close() {
                    throw new IllegalStateException("algorithm cleanup failed");
                }
            };
        }
    }

    public static final class CloseTrackingRankerFactory implements SimpleRankerFactory<String, String> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<String, String> apply(Optional<JsonNode> hyperparameter) {
            return new CloseTrackingRanker();
        }
    }

    private static final class CloseTrackingRanker implements Ranker<String, String> {
        private int closeCalls;
        private Thread closedOn;

        @Override
        public RankingResponse<String> rank(RankingRequest<String, String> request) {
            return RankingResponse.newResponse(List.of());
        }

        @Override
        public void close() {
            closeCalls++;
            closedOn = Thread.currentThread();
        }
    }

    public record TaggedRanker(
            String tag,
            AlgorithmDependencies dependencies) implements Ranker<String, String> {
        @Override
        public RankingResponse<String> rank(RankingRequest<String, String> request) {
            return RankingResponse.newResponse(List.of(), Map.of("tag", tag));
        }
    }
}
