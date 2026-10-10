package com.hotvect.onlineutils.serving;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
import com.hotvect.api.algodefinition.common.SimpleAlgorithmFactory;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmDownloadClient;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmDownloader;
import com.hotvect.onlineutils.experimentmanagement.algodownload.DownloadedAlgorithmArtifact;
import com.hotvect.onlineutils.experimentmanagement.algodownload.DownloadedAlgorithmArtifacts;
import com.hotvect.onlineutils.experimentmanagement.algodownload.DownloadedAlgorithmParameters;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.hotdeploy.AlgorithmArtifactProvider;
import com.hotvect.onlineutils.hotdeploy.AlgorithmGraph;
import com.hotvect.onlineutils.hotdeploy.AlgorithmGraphDependencies;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.hotdeploy.SharedNodeInterner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AlgorithmRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void reusesTheGraphWhileACandidateCanStillReachIt() {
        CountingAlgorithmDownloader downloader = new CountingAlgorithmDownloader(tempDir);

        try (AlgorithmRepository repository = repository(downloader);
                AlgorithmRepository.CachedAlgorithmGraph first = acquire(repository, algorithmMetadata("parameter-1"));
                AlgorithmRepository.CachedAlgorithmGraph second = acquire(repository, algorithmMetadata("parameter-1"))) {

            assertNotSame(first, second);
            assertSame(first.instance(), second.instance());
            assertEquals(1, downloader.jarDownloadCount());
            assertEquals(1, downloader.algorithmLoadCount());
            assertFalse(assertInstanceOf(TestAlgorithm.class, first.instance().algorithm()).isClosed());
        }
    }

    @Test
    void reusesPublishedIdentityAcrossDifferentArtifactLocations() {
        CountingAlgorithmDownloader downloader = new CountingAlgorithmDownloader(tempDir);
        AlgorithmMetadata firstLocation = algorithmMetadata(
                "parameter-1",
                "s3://first/test-algorithm.jar",
                "s3://first/parameter-1.zip");
        AlgorithmMetadata secondLocation = algorithmMetadata(
                "parameter-1",
                "s3://second/test-algorithm.jar",
                "s3://second/parameter-1.zip");

        try (AlgorithmRepository repository = repository(downloader);
                AlgorithmRepository.CachedAlgorithmGraph first = acquire(repository, firstLocation);
                AlgorithmRepository.CachedAlgorithmGraph second = acquire(repository, secondLocation)) {

            assertNotSame(first, second);
            assertSame(first.instance(), second.instance());
            assertEquals(1, downloader.jarDownloadCount());
            assertEquals(1, downloader.algorithmLoadCount());
        }
    }

    @Test
    void suppliesContainerBindingsOnlyToGraphsThatDeclareThem() {
        AlgorithmMetadata rootMetadata = parameterlessMetadata("root", "s3://bucket/root.jar");
        AlgorithmMetadata policyMetadata = parameterlessMetadata("policy", "s3://bucket/policy.jar");
        SharedProviderDownloader downloader = new SharedProviderDownloader(
                tempDir,
                Map.of(
                        rootMetadata.algorithmId(), applicationBoundArtifactDefinitions(rootMetadata),
                        policyMetadata.algorithmId(), Map.of(
                                "policy-algorithm-definition.json",
                                """
                                        {
                                          "algorithm_name": "policy",
                                          "algorithm_version": "1",
                                          "algorithm_factory_classname": "%s"
                                        }
                                        """.formatted(TestAlgorithmFactory.class.getName()))));
        AlgorithmInstance<TestAlgorithm> containerEncoder =
                AlgorithmInstance.externalAlgorithm("encoder", TestAlgorithm.class, new TestAlgorithm());

        try (AlgorithmRepository repository = new AlgorithmRepository(
                        downloader,
                        null,
                        new AlgorithmDependencies(Map.of("encoder", containerEncoder)));
                AlgorithmRepository.AlgorithmArtifactInspection rootInspection =
                        repository.inspectAlgorithm(rootMetadata);
                AlgorithmRepository.AlgorithmArtifactInspection policyInspection =
                        repository.inspectAlgorithm(policyMetadata);
                AlgorithmRepository.SharedArtifactCatalog sharedArtifacts = repository.sharedArtifactCatalog(
                        List.of(rootInspection, policyInspection));
                AlgorithmRepository.CachedAlgorithmGraph policy = repository.acquireComposedAlgorithm(
                        policyInspection,
                        Map.of(),
                        sharedArtifacts,
                        ExecutionContext.realtime(InputSemantic.ONLINE));
                AlgorithmRepository.CachedAlgorithmGraph root = repository.acquireComposedAlgorithm(
                        rootInspection,
                        Map.of(),
                        sharedArtifacts,
                        ExecutionContext.realtime(InputSemantic.ONLINE))) {

            assertTrue(dependencies(policy.instance()).isEmpty());
            assertSame(
                    containerEncoder,
                    dependencies(root.instance()).asMap().get("encoder"));
        }
    }

    @Test
    void resolvesSharedDependenciesThroughOneCanonicalEmsArtifact() {
        CanonicalSharedFactory.reset();
        AlgorithmMetadata firstMetadata = parameterlessMetadata("first", "s3://bucket/a-first.jar");
        AlgorithmMetadata secondMetadata = parameterlessMetadata("second", "s3://bucket/b-second.jar");
        SharedProviderDownloader downloader = new SharedProviderDownloader(
                tempDir,
                Map.of(
                        firstMetadata.algorithmId(), artifactDefinitions(
                                firstMetadata,
                                CanonicalSharedFactory.class),
                        secondMetadata.algorithmId(), artifactDefinitions(
                                secondMetadata,
                                OtherSharedFactory.class)));

        try (AlgorithmRepository repository = repository(downloader);
                AlgorithmRepository.AlgorithmArtifactInspection firstInspection = repository.inspectAlgorithm(
                        firstMetadata);
                AlgorithmRepository.AlgorithmArtifactInspection secondInspection = repository.inspectAlgorithm(
                        secondMetadata);
                AlgorithmRepository.SharedArtifactCatalog sharedArtifacts = repository.sharedArtifactCatalog(
                        List.of(firstInspection, secondInspection));
                AlgorithmRepository.CachedAlgorithmGraph first = repository.acquireComposedAlgorithm(
                        firstInspection,
                        Map.of(),
                        sharedArtifacts,
                        ExecutionContext.realtime(InputSemantic.ONLINE));
                AlgorithmRepository.CachedAlgorithmGraph second = repository.acquireComposedAlgorithm(
                        secondInspection,
                        Map.of(),
                        sharedArtifacts,
                        ExecutionContext.realtime(InputSemantic.ONLINE))) {

            AlgorithmInstance<?> firstShared = dependencies(first.instance()).asMap().get("shared");
            AlgorithmInstance<?> secondShared = dependencies(second.instance()).asMap().get("shared");
            assertSame(firstShared, secondShared);
            assertInstanceOf(CanonicalSharedAlgorithm.class, firstShared.algorithm());
            assertInstanceOf(CanonicalSharedAlgorithm.class, secondShared.algorithm());
            assertEquals(1, CanonicalSharedFactory.constructionCount());
        }
    }

    @Test
    void sharesAParameterizedNodeByAlgorithmIdAndUsesTheNewestLogicalDataDate() {
        AlgorithmMetadata firstMetadata = new AlgorithmMetadata(
                "first",
                "1",
                "first-root-p1",
                "s3://bucket/a-first.jar",
                "s3://bucket/first-root-p1.zip");
        AlgorithmMetadata secondMetadata = new AlgorithmMetadata(
                "second",
                "1",
                "second-root-p2",
                "s3://bucket/b-second.jar",
                "s3://bucket/second-root-p2.zip");
        SharedProviderDownloader downloader = new SharedProviderDownloader(
                tempDir,
                Map.of(
                        firstMetadata.algorithmId(), artifactDefinitions(
                                firstMetadata,
                                ParameterizedCanonicalSharedFactory.class),
                        secondMetadata.algorithmId(), artifactDefinitions(
                                secondMetadata,
                                OtherSharedFactory.class)),
                Map.of(
                        firstMetadata.algorithmId(), Map.of(
                                "first/algorithm-parameters.json", parameterMetadata(
                                        "first", "1", "first-root-p1"),
                                "shared/algorithm-parameters.json", parameterMetadataAt(
                                        "shared",
                                        "1",
                                        "canonical-shared-p1",
                                        "2026-08-15",
                                        "2026-08-16T00:00:00Z"),
                                "shared/model.parameter", "canonical-provider"),
                        secondMetadata.algorithmId(), Map.of(
                                "second/algorithm-parameters.json", parameterMetadata(
                                        "second", "1", "second-root-p2"),
                                "shared/algorithm-parameters.json", parameterMetadataAt(
                                        "shared",
                                        "1",
                                        "other-shared-p2",
                                        "2026-08-16",
                                        "2026-08-15T00:00:00Z"),
                                "shared/model.parameter", "other-provider")));

        try (AlgorithmRepository repository = repository(downloader);
                AlgorithmRepository.AlgorithmArtifactInspection firstInspection = repository.inspectAlgorithm(
                        firstMetadata);
                AlgorithmRepository.AlgorithmArtifactInspection secondInspection = repository.inspectAlgorithm(
                        secondMetadata);
                AlgorithmRepository.SharedArtifactCatalog sharedArtifacts = repository.sharedArtifactCatalog(
                        List.of(firstInspection, secondInspection));
                AlgorithmRepository.CachedAlgorithmGraph first = repository.acquireComposedAlgorithm(
                        firstInspection,
                        Map.of(),
                        sharedArtifacts,
                        ExecutionContext.realtime(InputSemantic.ONLINE));
                AlgorithmRepository.CachedAlgorithmGraph second = repository.acquireComposedAlgorithm(
                        secondInspection,
                        Map.of(),
                        sharedArtifacts,
                        ExecutionContext.realtime(InputSemantic.ONLINE))) {

            AlgorithmInstance<?> firstShared = dependencies(first.instance()).asMap().get("shared");
            AlgorithmInstance<?> secondShared = dependencies(second.instance()).asMap().get("shared");
            ParameterizedSharedAlgorithm shared = assertInstanceOf(
                    ParameterizedSharedAlgorithm.class,
                    firstShared.algorithm());
            assertSame(firstShared, secondShared);
            assertEquals("other-shared-p2", firstShared.algorithmParameterMetadata().parameterId());
            assertEquals("other-provider", shared.parameter());
            assertEquals(2, downloader.parameterDownloadCount());
        }
    }

    @Test
    void usesRanAtToBreakATieOnLogicalDataDate() {
        AlgorithmMetadata firstMetadata = new AlgorithmMetadata(
                "first", "1", "first-p1", "s3://bucket/a-first.jar", "s3://bucket/first-p1.zip");
        AlgorithmMetadata secondMetadata = new AlgorithmMetadata(
                "second", "1", "second-p2", "s3://bucket/b-second.jar", "s3://bucket/second-p2.zip");
        SharedProviderDownloader downloader = new SharedProviderDownloader(
                tempDir,
                Map.of(
                        firstMetadata.algorithmId(), artifactDefinitions(
                                firstMetadata,
                                ParameterizedCanonicalSharedFactory.class),
                        secondMetadata.algorithmId(), artifactDefinitions(
                                secondMetadata,
                                OtherSharedFactory.class)),
                Map.of(
                        firstMetadata.algorithmId(), parameterArchive(
                                "first",
                                "first-p1",
                                "shared-p1",
                                "2026-08-15",
                                "2026-08-15T08:00:00Z",
                                "state-p1"),
                        secondMetadata.algorithmId(), parameterArchive(
                                "second",
                                "second-p2",
                                "shared-p2",
                                "2026-08-15",
                                "2026-08-15T09:00:00Z",
                                "state-p2")));

        try (AlgorithmRepository repository = repository(downloader);
                AlgorithmRepository.AlgorithmArtifactInspection firstMetadataInspection = repository.inspectAlgorithm(firstMetadata);
                AlgorithmRepository.AlgorithmArtifactInspection secondMetadataInspection = repository.inspectAlgorithm(secondMetadata);
                AlgorithmRepository.SharedArtifactCatalog sharedArtifacts = repository.sharedArtifactCatalog(List.of(
                        firstMetadataInspection,
                        secondMetadataInspection));
                AlgorithmRepository.CachedAlgorithmGraph first = repository.acquireComposedAlgorithm(
                        firstMetadataInspection, Map.of(), sharedArtifacts,
                        ExecutionContext.realtime(InputSemantic.ONLINE))) {
            AlgorithmInstance<?> shared = dependencies(first.instance())
                    .asMap().get("shared");

            assertEquals("shared-p2", shared.algorithmParameterMetadata().parameterId());
            assertEquals("state-p2", assertInstanceOf(
                    ParameterizedSharedAlgorithm.class,
                    shared.algorithm()).parameter());
        }
    }

    @Test
    void rejectsSharedParameterMetadataWithoutLogicalDataDate() {
        AlgorithmMetadata metadata = new AlgorithmMetadata(
                "first", "1", "first-p1", "s3://bucket/a-first.jar", "s3://bucket/first-p1.zip");
        SharedProviderDownloader downloader = new SharedProviderDownloader(
                tempDir,
                Map.of(metadata.algorithmId(), artifactDefinitions(
                        metadata,
                        ParameterizedCanonicalSharedFactory.class)),
                Map.of(metadata.algorithmId(), Map.of(
                        "first/algorithm-parameters.json", parameterMetadata("first", "1", "first-p1"),
                        "shared/algorithm-parameters.json", """
                                {
                                  "algorithm_name": "shared",
                                  "algorithm_version": "1",
                                  "parameter_id": "shared-p1",
                                  "ran_at": "2026-08-15T08:00:00Z"
                                }
                                """,
                        "shared/model.parameter", "state-p1")));

        try (AlgorithmRepository repository = repository(downloader);
                AlgorithmRepository.AlgorithmArtifactInspection inspection = repository.inspectAlgorithm(metadata)) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.sharedArtifactCatalog(List.of(inspection)));

            assertTrue(failure.getMessage().contains("shared@1"));
            assertTrue(failure.getMessage().contains("last_test_time"));
            assertEquals(1, downloader.stagedParameters().size());
            assertFalse(Files.exists(downloader.stagedParameters().getFirst()));
        }
    }

    @Test
    void loadsCanonicalCodeWithParametersFromAnotherArtifact() {
        AlgorithmMetadata firstMetadata = parameterlessMetadata("first", "s3://bucket/a-first.jar");
        AlgorithmMetadata secondMetadata = new AlgorithmMetadata(
                "second",
                "1",
                "second-root-p2",
                "s3://bucket/b-second.jar",
                "s3://bucket/second-root-p2.zip");
        SharedProviderDownloader downloader = new SharedProviderDownloader(
                tempDir,
                Map.of(
                        firstMetadata.algorithmId(), artifactDefinitions(
                                firstMetadata,
                                ParameterizedCanonicalSharedFactory.class),
                        secondMetadata.algorithmId(), artifactDefinitions(
                                secondMetadata,
                                OtherSharedFactory.class)),
                Map.of(secondMetadata.algorithmId(), Map.of(
                        "second/algorithm-parameters.json", parameterMetadata(
                                "second", "1", "second-root-p2"),
                        "shared/algorithm-parameters.json", parameterMetadata(
                                "shared", "1", "canonical-shared-p2"),
                        "shared/model.parameter", "parameter-archive-provider")));

        try (AlgorithmRepository repository = repository(downloader);
                AlgorithmRepository.AlgorithmArtifactInspection firstInspection = repository.inspectAlgorithm(
                        firstMetadata);
                AlgorithmRepository.AlgorithmArtifactInspection secondInspection = repository.inspectAlgorithm(
                        secondMetadata);
                AlgorithmRepository.SharedArtifactCatalog sharedArtifacts = repository.sharedArtifactCatalog(
                        List.of(firstInspection, secondInspection));
                AlgorithmRepository.CachedAlgorithmGraph first = repository.acquireComposedAlgorithm(
                        firstInspection,
                        Map.of(),
                        sharedArtifacts,
                        ExecutionContext.realtime(InputSemantic.ONLINE));
                AlgorithmRepository.CachedAlgorithmGraph second = repository.acquireComposedAlgorithm(
                        secondInspection,
                        Map.of(),
                        sharedArtifacts,
                        ExecutionContext.realtime(InputSemantic.ONLINE))) {

            AlgorithmInstance<?> firstShared = dependencies(first.instance()).asMap().get("shared");
            AlgorithmInstance<?> secondShared = dependencies(second.instance()).asMap().get("shared");
            ParameterizedSharedAlgorithm shared = assertInstanceOf(
                    ParameterizedSharedAlgorithm.class,
                    firstShared.algorithm());
            assertSame(firstShared, secondShared);
            assertEquals("canonical-shared-p2", firstShared.algorithmParameterMetadata().parameterId());
            assertEquals("parameter-archive-provider", shared.parameter());
        }
    }

    @Test
    void refreshesSharedParametersWithoutReplacingTheSelectedClassloader() {
        AlgorithmMetadata firstP1 = new AlgorithmMetadata(
                "first",
                "1",
                "first-p1",
                "s3://bucket/a-first.jar",
                "s3://bucket/first-p1.zip");
        AlgorithmMetadata firstP3 = new AlgorithmMetadata(
                "first",
                "1",
                "first-p3",
                "s3://bucket/a-first.jar",
                "s3://bucket/first-p3.zip");
        AlgorithmMetadata secondP2 = new AlgorithmMetadata(
                "second",
                "1",
                "second-p2",
                "s3://bucket/b-second.jar",
                "s3://bucket/second-p2.zip");
        Map<String, Map<String, String>> archives = Map.of(
                "first-p1", parameterArchive(
                        "first",
                        "first-p1",
                        "shared-p1",
                        "2026-08-13",
                        "2026-08-15T00:00:00Z",
                        "state-p1"),
                "second-p2", parameterArchive(
                        "second",
                        "second-p2",
                        "shared-p2",
                        "2026-08-14",
                        "2026-08-14T00:00:00Z",
                        "state-p2"),
                "first-p3", parameterArchive(
                        "first",
                        "first-p3",
                        "shared-p3",
                        "2026-08-15",
                        "2026-08-13T00:00:00Z",
                        "state-p3"));
        SharedProviderDownloader downloader = new SharedProviderDownloader(
                tempDir,
                Map.of(
                        firstP1.algorithmId(), artifactDefinitions(
                                firstP1,
                                ParameterizedCanonicalSharedFactory.class),
                        secondP2.algorithmId(), artifactDefinitions(secondP2, OtherSharedFactory.class)),
                metadata -> archives.get(metadata.latestAlgorithmParameter()));

        try (AlgorithmRepository repository = repository(downloader)) {
            AlgorithmInstance<?> oldShared;
            AlgorithmRepository.CachedAlgorithmGraph oldFirst;
            AlgorithmRepository.CachedAlgorithmGraph oldSecond;
            try (AlgorithmRepository.AlgorithmArtifactInspection firstP1Inspection = repository.inspectAlgorithm(firstP1);
                    AlgorithmRepository.AlgorithmArtifactInspection secondP2Inspection = repository.inspectAlgorithm(secondP2);
                    AlgorithmRepository.SharedArtifactCatalog firstRefresh = repository.sharedArtifactCatalog(List.of(
                            firstP1Inspection,
                            secondP2Inspection))) {
                oldFirst = repository.acquireComposedAlgorithm(
                        firstP1Inspection, Map.of(), firstRefresh,
                        ExecutionContext.realtime(InputSemantic.ONLINE));
                oldSecond = repository.acquireComposedAlgorithm(
                        secondP2Inspection, Map.of(), firstRefresh,
                        ExecutionContext.realtime(InputSemantic.ONLINE));
                oldShared = dependencies(oldFirst.instance()).asMap().get("shared");
                assertSame(oldShared, dependencies(oldSecond.instance()).asMap().get("shared"));
                assertEquals("state-p2", assertInstanceOf(
                        ParameterizedSharedAlgorithm.class,
                        oldShared.algorithm()).parameter());
            }
            assertEquals(2, downloader.parameterDownloadCount());

            AlgorithmRepository.CachedAlgorithmGraph refreshedFirst;
            AlgorithmRepository.CachedAlgorithmGraph refreshedSecond;
            AlgorithmInstance<?> refreshedShared;
            try (AlgorithmRepository.AlgorithmArtifactInspection firstP3Inspection = repository.inspectAlgorithm(firstP3);
                    AlgorithmRepository.AlgorithmArtifactInspection secondP2Inspection = repository.inspectAlgorithm(secondP2);
                    AlgorithmRepository.SharedArtifactCatalog secondRefresh = repository.sharedArtifactCatalog(List.of(
                            firstP3Inspection,
                            secondP2Inspection))) {
                refreshedFirst = repository.acquireComposedAlgorithm(
                        firstP3Inspection, Map.of(), secondRefresh,
                        ExecutionContext.realtime(InputSemantic.ONLINE));
                refreshedSecond = repository.acquireComposedAlgorithm(
                        secondP2Inspection, Map.of(), secondRefresh,
                        ExecutionContext.realtime(InputSemantic.ONLINE));
                refreshedShared = dependencies(refreshedFirst.instance())
                        .asMap().get("shared");
                assertSame(
                        refreshedShared,
                        dependencies(refreshedSecond.instance()).asMap().get("shared"));
                assertNotSame(oldShared, refreshedShared);
                assertSame(oldFirst.rootArtifactClassLoader(), refreshedFirst.rootArtifactClassLoader());
                assertEquals("shared-p3", refreshedShared.algorithmParameterMetadata().parameterId());
                assertEquals("state-p3", assertInstanceOf(
                        ParameterizedSharedAlgorithm.class,
                        refreshedShared.algorithm()).parameter());
            }
            assertEquals(4, downloader.parameterDownloadCount());

            try (AlgorithmRepository.AlgorithmArtifactInspection firstP3Inspection = repository.inspectAlgorithm(firstP3);
                    AlgorithmRepository.AlgorithmArtifactInspection secondP2Inspection = repository.inspectAlgorithm(secondP2);
                    AlgorithmRepository.SharedArtifactCatalog unchangedRefresh = repository.sharedArtifactCatalog(List.of(
                            firstP3Inspection,
                            secondP2Inspection));
                    AlgorithmRepository.CachedAlgorithmGraph unchangedFirst = repository.acquireComposedAlgorithm(
                            firstP3Inspection, Map.of(), unchangedRefresh,
                            ExecutionContext.realtime(InputSemantic.ONLINE));
                    AlgorithmRepository.CachedAlgorithmGraph unchangedSecond = repository.acquireComposedAlgorithm(
                            secondP2Inspection, Map.of(), unchangedRefresh,
                            ExecutionContext.realtime(InputSemantic.ONLINE))) {
                assertNotSame(refreshedFirst, unchangedFirst);
                assertNotSame(refreshedSecond, unchangedSecond);
                assertSame(refreshedFirst.instance(), unchangedFirst.instance());
                assertSame(refreshedSecond.instance(), unchangedSecond.instance());
                assertSame(
                        refreshedShared,
                        dependencies(unchangedFirst.instance()).asMap().get("shared"));
            }
            assertEquals(4, downloader.parameterDownloadCount());

            try (AlgorithmRepository.AlgorithmArtifactInspection firstP1Inspection = repository.inspectAlgorithm(firstP1);
                    AlgorithmRepository.AlgorithmArtifactInspection secondP2Inspection = repository.inspectAlgorithm(secondP2);
                    AlgorithmRepository.SharedArtifactCatalog rollbackRefresh = repository.sharedArtifactCatalog(List.of(
                            firstP1Inspection,
                            secondP2Inspection))) {
                assertEquals(5, downloader.parameterDownloadCount());
            }
            oldFirst.close();
            oldSecond.close();
            refreshedFirst.close();
            refreshedSecond.close();
        }
    }

    @Test
    void reusesLiveParameterizedSharedNodeWhenARefreshIntroducesANewRoot() {
        AlgorithmMetadata firstMetadata = new AlgorithmMetadata(
                "first", "1", "first-p1", "s3://bucket/a-first.jar", "s3://bucket/a-first-p1.zip");
        AlgorithmMetadata secondMetadata = new AlgorithmMetadata(
                "second", "1", "second-p1", "s3://bucket/b-second.jar", "s3://bucket/b-second-p1.zip");
        Map<String, Map<String, String>> archives = Map.of(
                "first-p1", parameterArchive(
                        "first",
                        "first-p1",
                        "shared-p1",
                        "2026-08-15",
                        "2026-08-15T00:00:00Z",
                        "state-p1"),
                "second-p1", parameterArchive(
                        "second",
                        "second-p1",
                        "shared-p1",
                        "2026-08-15",
                        "2026-08-15T00:00:00Z",
                        "state-p1"));
        SharedProviderDownloader downloader = new SharedProviderDownloader(
                tempDir,
                Map.of(
                        firstMetadata.algorithmId(), artifactDefinitions(
                                firstMetadata,
                                ParameterizedCanonicalSharedFactory.class),
                        secondMetadata.algorithmId(), artifactDefinitions(secondMetadata, OtherSharedFactory.class)),
                metadata -> archives.get(metadata.latestAlgorithmParameter()));

        try (AlgorithmRepository repository = repository(downloader)) {
            AlgorithmRepository.CachedAlgorithmGraph first;
            AlgorithmInstance<?> shared;
            try (AlgorithmRepository.AlgorithmArtifactInspection firstMetadataInspection = repository.inspectAlgorithm(firstMetadata);
                    AlgorithmRepository.SharedArtifactCatalog firstRefresh = repository.sharedArtifactCatalog(
                            List.of(firstMetadataInspection))) {
                first = repository.acquireComposedAlgorithm(
                        firstMetadataInspection,
                        Map.of(),
                        firstRefresh,
                        ExecutionContext.realtime(InputSemantic.ONLINE));
                shared = dependencies(first.instance()).asMap().get("shared");
            }

            try (AlgorithmRepository.AlgorithmArtifactInspection firstMetadataInspection = repository.inspectAlgorithm(firstMetadata);
                    AlgorithmRepository.AlgorithmArtifactInspection secondMetadataInspection = repository.inspectAlgorithm(secondMetadata);
                    AlgorithmRepository.SharedArtifactCatalog secondRefresh = repository.sharedArtifactCatalog(List.of(
                            firstMetadataInspection,
                            secondMetadataInspection));
                    AlgorithmRepository.CachedAlgorithmGraph unchangedFirst = repository.acquireComposedAlgorithm(
                            firstMetadataInspection,
                            Map.of(),
                            secondRefresh,
                            ExecutionContext.realtime(InputSemantic.ONLINE));
                    AlgorithmRepository.CachedAlgorithmGraph newSecond = repository.acquireComposedAlgorithm(
                            secondMetadataInspection,
                            Map.of(),
                            secondRefresh,
                            ExecutionContext.realtime(InputSemantic.ONLINE))) {

                assertNotSame(first, unchangedFirst);
                assertSame(first.instance(), unchangedFirst.instance());
                assertSame(
                        shared,
                        dependencies(newSecond.instance()).asMap().get("shared"));
                assertEquals("state-p1", assertInstanceOf(
                        ParameterizedSharedAlgorithm.class,
                        shared.algorithm()).parameter());
            }
            assertEquals(2, downloader.parameterDownloadCount());
            first.close();
        }
    }

    @Test
    void keepsRuntimeSharedNodesAliveUntilTheirLastParentLeaseIsReleased() {
        CanonicalSharedFactory.reset();
        AlgorithmMetadata firstMetadata = parameterlessMetadata("first", "s3://bucket/a-first.jar");
        AlgorithmMetadata secondMetadata = parameterlessMetadata("second", "s3://bucket/b-second.jar");
        SharedProviderDownloader downloader = new SharedProviderDownloader(
                tempDir,
                Map.of(
                        firstMetadata.algorithmId(), artifactDefinitions(
                                firstMetadata,
                                CanonicalSharedFactory.class),
                        secondMetadata.algorithmId(), artifactDefinitions(
                                secondMetadata,
                                OtherSharedFactory.class)));

        try (AlgorithmRepository repository = repository(downloader)) {
            SharedNodeLifecycleObservation observation = observeSharedNodeLifecycle(
                    repository,
                    downloader,
                    firstMetadata,
                    secondMetadata);

            assertTrue(Files.exists(observation.sharedArtifact()));
            assertEquals(1, CanonicalSharedFactory.constructionCount());
            observation.firstGraph().close();
            assertTrue(observation.firstRoot().isClosed());

            assertFalse(observation.sharedAlgorithm().isClosed());
            assertTrue(Files.exists(observation.sharedArtifact()));

            observation.secondGraph().close();
            assertTrue(observation.secondRoot().isClosed());
            assertTrue(observation.sharedAlgorithm().isClosed());
            assertFalse(Files.exists(observation.sharedArtifact()));

            assertEquals(1, observation.sharedAlgorithm().closeCallCount());
            assertNoRegisteredOwners(repository);

            SharedNodeLifecycleObservation replacement = observeSharedNodeLifecycle(
                    repository, downloader, firstMetadata, secondMetadata);
            assertNotSame(observation.sharedAlgorithm(), replacement.sharedAlgorithm());
            replacement.firstGraph().close();
            replacement.secondGraph().close();
            assertNoRegisteredOwners(repository);
        }
    }

    @Test
    void internsNestedStaticSharedNodesAcrossRootCompositions() {
        CanonicalSharedFactory.reset();
        NestedSharedFactory.reset();
        AlgorithmMetadata firstMetadata = parameterlessMetadata("first", "s3://bucket/a-first.jar");
        AlgorithmMetadata secondMetadata = parameterlessMetadata("second", "s3://bucket/b-second.jar");
        SharedProviderDownloader downloader = new SharedProviderDownloader(
                tempDir,
                Map.of(
                        firstMetadata.algorithmId(), nestedSharedArtifactDefinitions(firstMetadata),
                        secondMetadata.algorithmId(), nestedSharedArtifactDefinitions(secondMetadata)));

        try (AlgorithmRepository repository = repository(downloader);
                AlgorithmRepository.AlgorithmArtifactInspection firstInspection = repository.inspectAlgorithm(
                        firstMetadata);
                AlgorithmRepository.AlgorithmArtifactInspection secondInspection = repository.inspectAlgorithm(
                        secondMetadata);
                AlgorithmRepository.SharedArtifactCatalog sharedArtifacts = repository.sharedArtifactCatalog(
                        List.of(firstInspection, secondInspection));
                AlgorithmRepository.CachedAlgorithmGraph first = repository.acquireComposedAlgorithm(
                        firstInspection,
                        Map.of(),
                        sharedArtifacts,
                        ExecutionContext.realtime(InputSemantic.ONLINE));
                AlgorithmRepository.CachedAlgorithmGraph second = repository.acquireComposedAlgorithm(
                        secondInspection,
                        Map.of(),
                        sharedArtifacts,
                        ExecutionContext.realtime(InputSemantic.ONLINE))) {

            AlgorithmInstance<?> firstShared = dependencies(first.instance()).asMap().get("shared");
            AlgorithmInstance<?> secondShared = dependencies(second.instance()).asMap().get("shared");
            AlgorithmInstance<?> firstInner = dependencies(firstShared).asMap().get("inner");
            AlgorithmInstance<?> secondInner = dependencies(secondShared).asMap().get("inner");
            assertSame(firstShared, secondShared);
            assertSame(firstInner, secondInner);
            assertEquals(1, NestedSharedFactory.constructionCount());
            assertEquals(1, CanonicalSharedFactory.constructionCount());
        }
    }

    @Test
    void eachSharedParentRetainsItsNestedSharedDependencies() {
        CanonicalSharedFactory.reset();
        AlgorithmMetadata firstMetadata = parameterlessMetadata("first", "s3://bucket/a-first.jar");
        AlgorithmMetadata secondMetadata = parameterlessMetadata("second", "s3://bucket/b-second.jar");
        SharedProviderDownloader downloader = new SharedProviderDownloader(
                tempDir,
                Map.of(
                        firstMetadata.algorithmId(), branchingSharedArtifactDefinitions(firstMetadata, true),
                        secondMetadata.algorithmId(), branchingSharedArtifactDefinitions(secondMetadata, false)));

        try (AlgorithmRepository repository = repository(downloader)) {
            AlgorithmRepository.CachedAlgorithmGraph first;
            AlgorithmRepository.CachedAlgorithmGraph second;
            CanonicalSharedAlgorithm inner;
            try (AlgorithmRepository.AlgorithmArtifactInspection firstInspection =
                            repository.inspectAlgorithm(firstMetadata);
                    AlgorithmRepository.AlgorithmArtifactInspection secondInspection =
                            repository.inspectAlgorithm(secondMetadata);
                    AlgorithmRepository.SharedArtifactCatalog sharedArtifacts = repository.sharedArtifactCatalog(
                            List.of(firstInspection, secondInspection))) {
                first = repository.acquireComposedAlgorithm(
                        firstInspection,
                        Map.of(),
                        sharedArtifacts,
                        ExecutionContext.realtime(InputSemantic.ONLINE));
                second = repository.acquireComposedAlgorithm(
                        secondInspection,
                        Map.of(),
                        sharedArtifacts,
                        ExecutionContext.realtime(InputSemantic.ONLINE));

                AlgorithmInstance<?> outerA = dependencies(first.instance()).asMap().get("outer-a");
                AlgorithmInstance<?> outerB = dependencies(first.instance()).asMap().get("outer-b");
                AlgorithmInstance<?> retainedOuterB = dependencies(second.instance()).asMap().get("outer-b");
                assertSame(outerB, retainedOuterB);
                AlgorithmInstance<?> innerInstance = dependencies(outerA).asMap().get("inner");
                assertSame(innerInstance, dependencies(outerB).asMap().get("inner"));
                inner = assertInstanceOf(CanonicalSharedAlgorithm.class, innerInstance.algorithm());
            }

            first.close();
            assertFalse(inner.isClosed());

            second.close();
            assertTrue(inner.isClosed());
            assertEquals(1, inner.closeCallCount());
        }
    }

    @Test
    void usesCanonicalSharedDependencyClosureWhenPackagedCopiesDiffer() {
        AlgorithmMetadata canonicalMetadata = parameterlessMetadata("canonical", "s3://bucket/a-canonical.jar");
        AlgorithmMetadata otherMetadata = parameterlessMetadata("other", "s3://bucket/b-other.jar");
        SharedProviderDownloader downloader = new SharedProviderDownloader(
                tempDir,
                Map.of(
                        canonicalMetadata.algorithmId(), nestedSharedArtifactDefinitions(canonicalMetadata),
                        otherMetadata.algorithmId(), artifactDefinitions(otherMetadata, OtherSharedFactory.class)));

        try (AlgorithmRepository repository = repository(downloader);
                AlgorithmRepository.AlgorithmArtifactInspection canonicalInspection =
                        repository.inspectAlgorithm(canonicalMetadata);
                AlgorithmRepository.AlgorithmArtifactInspection otherInspection =
                        repository.inspectAlgorithm(otherMetadata)) {
            try (AlgorithmRepository.SharedArtifactCatalog sharedArtifacts = repository.sharedArtifactCatalog(
                            List.of(canonicalInspection, otherInspection));
                    AlgorithmRepository.CachedAlgorithmGraph other = repository.acquireComposedAlgorithm(
                            otherInspection,
                            Map.of(),
                            sharedArtifacts,
                            ExecutionContext.realtime(InputSemantic.ONLINE))) {
                AlgorithmInstance<?> shared =
                        dependencies(other.instance()).asMap().get("shared");

                assertInstanceOf(NestedSharedAlgorithm.class, shared.algorithm());
                assertInstanceOf(
                        CanonicalSharedAlgorithm.class,
                        dependencies(shared).asMap().get("inner").algorithm());
            }
        }
    }

    @Test
    void suppliesGlobalApplicationBindingsToTheCanonicalSharedDependencyClosure() {
        NestedSharedFactory.reset();
        AlgorithmMetadata canonicalMetadata = parameterlessMetadata("canonical", "s3://bucket/a-canonical.jar");
        AlgorithmMetadata otherMetadata = parameterlessMetadata("other", "s3://bucket/b-other.jar");
        SharedProviderDownloader downloader = new SharedProviderDownloader(
                tempDir,
                Map.of(
                        canonicalMetadata.algorithmId(), applicationBoundSharedArtifactDefinitions(
                                canonicalMetadata),
                        otherMetadata.algorithmId(), artifactDefinitions(
                                otherMetadata,
                                OtherSharedFactory.class)));
        AlgorithmInstance<TestAlgorithm> applicationEncoder =
                AlgorithmInstance.externalAlgorithm("encoder", TestAlgorithm.class, new TestAlgorithm());

        try (AlgorithmRepository repository = new AlgorithmRepository(
                        downloader,
                        null,
                        new AlgorithmDependencies(Map.of("encoder", applicationEncoder)));
                AlgorithmRepository.AlgorithmArtifactInspection canonicalInspection =
                        repository.inspectAlgorithm(canonicalMetadata);
                AlgorithmRepository.AlgorithmArtifactInspection otherInspection =
                        repository.inspectAlgorithm(otherMetadata)) {
            try (AlgorithmRepository.SharedArtifactCatalog sharedArtifacts = repository.sharedArtifactCatalog(
                            List.of(canonicalInspection, otherInspection));
                    AlgorithmRepository.CachedAlgorithmGraph other = repository.acquireComposedAlgorithm(
                            otherInspection,
                            Map.of(),
                            sharedArtifacts,
                            ExecutionContext.realtime(InputSemantic.ONLINE));
                    AlgorithmRepository.CachedAlgorithmGraph canonical = repository.acquireComposedAlgorithm(
                            canonicalInspection,
                            Map.of(),
                            sharedArtifacts,
                            ExecutionContext.realtime(InputSemantic.ONLINE))) {

                AlgorithmInstance<?> otherShared =
                        dependencies(other.instance()).asMap().get("shared");
                AlgorithmInstance<?> canonicalShared =
                        dependencies(canonical.instance()).asMap().get("shared");
                assertSame(otherShared, canonicalShared);
                assertSame(
                        applicationEncoder,
                        dependencies(otherShared).asMap().get("encoder"));
                assertEquals(1, NestedSharedFactory.constructionCount());
            }
        }
    }

    @Test
    void rebuildsSharedParentWhenItsSharedChildParametersRefresh() {
        NestedSharedFactory.reset();
        AlgorithmMetadata rootP1 = new AlgorithmMetadata(
                "root", "1", "root-p1", "s3://bucket/root.jar", "s3://bucket/root-p1.zip");
        AlgorithmMetadata rootP2 = new AlgorithmMetadata(
                "root", "1", "root-p2", "s3://bucket/root.jar", "s3://bucket/root-p2.zip");
        Map<String, Map<String, String>> archives = Map.of(
                "root-p1", Map.of(
                        "root/algorithm-parameters.json", parameterMetadata("root", "1", "root-p1"),
                        "inner/algorithm-parameters.json", parameterMetadataAt(
                                "inner", "1", "inner-p1", "2026-08-14", "2026-08-14T00:00:00Z"),
                        "inner/model.parameter", "inner-state-p1"),
                "root-p2", Map.of(
                        "root/algorithm-parameters.json", parameterMetadata("root", "1", "root-p2"),
                        "inner/algorithm-parameters.json", parameterMetadataAt(
                                "inner", "1", "inner-p2", "2026-08-15", "2026-08-15T00:00:00Z"),
                        "inner/model.parameter", "inner-state-p2"));
        SharedProviderDownloader downloader = new SharedProviderDownloader(
                tempDir,
                Map.of(rootP1.algorithmId(), nestedParameterizedSharedArtifactDefinitions(rootP1)),
                metadata -> archives.get(metadata.latestAlgorithmParameter()));

        try (AlgorithmRepository repository = repository(downloader)) {
            AlgorithmInstance<?> oldOuter;
            AlgorithmInstance<?> oldInner;
            AlgorithmRepository.CachedAlgorithmGraph oldRoot;
            try (AlgorithmRepository.AlgorithmArtifactInspection rootP1Inspection = repository.inspectAlgorithm(rootP1);
                    AlgorithmRepository.SharedArtifactCatalog firstRefresh = repository.sharedArtifactCatalog(
                            List.of(rootP1Inspection))) {
                oldRoot = repository.acquireComposedAlgorithm(
                        rootP1Inspection, Map.of(), firstRefresh,
                        ExecutionContext.realtime(InputSemantic.ONLINE));
                oldOuter = dependencies(oldRoot.instance()).asMap().get("shared");
                oldInner = dependencies(oldOuter).asMap().get("inner");
                assertEquals("inner-state-p1", assertInstanceOf(
                        ParameterizedSharedAlgorithm.class,
                        oldInner.algorithm()).parameter());
            }

            AlgorithmRepository.CachedAlgorithmGraph newRoot;
            try (AlgorithmRepository.AlgorithmArtifactInspection rootP2Inspection = repository.inspectAlgorithm(rootP2);
                    AlgorithmRepository.SharedArtifactCatalog secondRefresh = repository.sharedArtifactCatalog(
                            List.of(rootP2Inspection))) {
                newRoot = repository.acquireComposedAlgorithm(
                        rootP2Inspection, Map.of(), secondRefresh,
                        ExecutionContext.realtime(InputSemantic.ONLINE));
                AlgorithmInstance<?> newOuter =
                        dependencies(newRoot.instance()).asMap().get("shared");
                AlgorithmInstance<?> newInner = dependencies(newOuter).asMap().get("inner");
                assertNotSame(oldOuter, newOuter);
                assertNotSame(oldInner, newInner);
                assertEquals("inner-state-p2", assertInstanceOf(
                        ParameterizedSharedAlgorithm.class,
                        newInner.algorithm()).parameter());
            }

            assertEquals(2, NestedSharedFactory.constructionCount());
            oldRoot.close();
            newRoot.close();
        }
    }

    @Test
    void rejectsSlotDependenciesInsideSharedAlgorithms() {
        AlgorithmMetadata metadata = parameterlessMetadata("root", "s3://bucket/root.jar");
        SharedProviderDownloader downloader = new SharedProviderDownloader(
                tempDir,
                Map.of(metadata.algorithmId(), Map.of(
                        "root-algorithm-definition.json",
                        """
                                {
                                  "algorithm_name": "root",
                                  "algorithm_version": "1",
                                  "algorithm_factory_classname": "%s",
                                  "dependencies": {"shared@1": {"scope": "shared"}}
                                }
                                """.formatted(SharedConsumerFactory.class.getName()),
                        "shared-algorithm-definition.json",
                        """
                                {
                                  "algorithm_name": "shared",
                                  "algorithm_version": "1",
                                  "algorithm_factory_classname": "%s",
                                  "dependencies": {"policies": {"scope": "slot"}}
                                }
                                """.formatted(NestedSharedFactory.class.getName()))));

        try (AlgorithmRepository repository = repository(downloader);
                AlgorithmRepository.AlgorithmArtifactInspection inspection = repository.inspectAlgorithm(metadata)) {

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> repository.sharedArtifactCatalog(List.of(inspection)));

            assertTrue(failure.getMessage().contains("Shared algorithm shared@1"));
            assertTrue(failure.getMessage().contains("slot dependency policies"));
        }
    }

    @Test
    void keepsDifferentSharedVersionsInDifferentEmsArtifactClassloaders() {
        AlgorithmMetadata firstMetadata = parameterlessMetadata("first", "s3://bucket/a-first.jar");
        AlgorithmMetadata secondMetadata = parameterlessMetadata("second", "s3://bucket/b-second.jar");
        SharedProviderDownloader downloader = new SharedProviderDownloader(
                tempDir,
                Map.of(
                        firstMetadata.algorithmId(), artifactDefinitions(
                                firstMetadata,
                                "1",
                                CanonicalSharedFactory.class),
                        secondMetadata.algorithmId(), artifactDefinitions(
                                secondMetadata,
                                "2",
                                OtherSharedFactory.class)));

        try (AlgorithmRepository repository = repository(downloader);
                AlgorithmRepository.AlgorithmArtifactInspection firstInspection = repository.inspectAlgorithm(
                        firstMetadata);
                AlgorithmRepository.AlgorithmArtifactInspection secondInspection = repository.inspectAlgorithm(
                        secondMetadata);
                AlgorithmRepository.SharedArtifactCatalog sharedArtifacts = repository.sharedArtifactCatalog(
                        List.of(firstInspection, secondInspection));
                AlgorithmRepository.CachedAlgorithmGraph first = repository.acquireComposedAlgorithm(
                        firstInspection,
                        Map.of(),
                        sharedArtifacts,
                        ExecutionContext.realtime(InputSemantic.ONLINE));
                AlgorithmRepository.CachedAlgorithmGraph second = repository.acquireComposedAlgorithm(
                        secondInspection,
                        Map.of(),
                        sharedArtifacts,
                        ExecutionContext.realtime(InputSemantic.ONLINE))) {

            AlgorithmInstance<?> firstShared = dependencies(first.instance()).asMap().get("shared");
            AlgorithmInstance<?> secondShared = dependencies(second.instance()).asMap().get("shared");
            assertEquals(new AlgorithmId("shared", "1"), firstShared.algorithmDefinition().algorithmId());
            assertEquals(new AlgorithmId("shared", "2"), secondShared.algorithmDefinition().algorithmId());
            assertNotSame(firstShared, secondShared);
            assertInstanceOf(CanonicalSharedAlgorithm.class, firstShared.algorithm());
            assertInstanceOf(OtherSharedAlgorithm.class, secondShared.algorithm());
        }
    }

    @Test
    void loadsAndReusesParameterlessAlgorithmGraphs() {
        CountingAlgorithmDownloader downloader = new CountingAlgorithmDownloader(tempDir);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        try (AlgorithmRepository repository = new AlgorithmRepository(
                        downloader,
                        meterRegistry,
                        AlgorithmDependencies.empty());
                AlgorithmRepository.CachedAlgorithmGraph first = acquire(repository, parameterlessAlgorithmMetadata());
                AlgorithmRepository.CachedAlgorithmGraph second = acquire(repository, parameterlessAlgorithmMetadata())) {

            assertNotSame(first, second);
            assertSame(first.instance(), second.instance());
            assertEquals(1, downloader.jarDownloadCount());
            assertEquals(1, downloader.algorithmLoadCount());
            assertTrue(meterRegistry.getMeters().isEmpty());
        }
    }

    @Test
    void keepsOneParameterAgeGaugeUntilEveryGraphLeaseIsReleased() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AlgorithmRepository.AlgorithmAgeMetrics metrics =
                new AlgorithmRepository.AlgorithmAgeMetrics(meterRegistry, new Object());
        Instant ranAt = Instant.now().minusSeconds(60);

        AlgorithmRepository.AlgorithmAgeMetricLease first =
                metrics.acquire("test-algorithm@1.0.0", "parameter-1", ranAt);
        AlgorithmRepository.AlgorithmAgeMetricLease second =
                metrics.acquire("test-algorithm@1.0.0", "parameter-1", ranAt);

        assertEquals(1, meterRegistry.getMeters().size());
        assertTrue(Double.isFinite(parameterAgeGauge(meterRegistry).value()));

        first.close();

        assertNotNull(parameterAgeGauge(meterRegistry));
        assertTrue(Double.isFinite(parameterAgeGauge(meterRegistry).value()));

        second.close();

        assertNull(meterRegistry.find("ems.algorithm_parameter.age").gauge());
        metrics.close();
    }

    @Test
    void rejectsParameterizedGraphsWithoutParameterMetadata() {
        CountingAlgorithmDownloader downloader = new CountingAlgorithmDownloader(tempDir, true);

        try (AlgorithmRepository repository = repository(downloader)) {
            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> acquire(repository, algorithmMetadata("parameter-1")));

            assertEquals(
                    "Parameterized algorithm loaded without parameter metadata for test-algorithm@1.0.0",
                    error.getMessage());
        }
    }

    @Test
    void closesTheGraphAndArtifactWhenTheirLastLeaseIsReleased() {
        CountingAlgorithmDownloader downloader = new CountingAlgorithmDownloader(tempDir);

        try (AlgorithmRepository repository = repository(downloader)) {
            GraphObservation observation = observeGraph(repository, "parameter-1");
            Path stagedJar = downloader.stagedJars().getFirst();

            observation.graph().close();

            assertTrue(observation.algorithm().isClosed());
            assertFalse(Files.exists(stagedJar));
            assertEquals(1, observation.algorithm().closeCallCount());
            assertNoRegisteredOwners(repository);
        }
    }

    @Test
    void concurrentLeaseReleaseClosesTheGraphAndArtifactExactlyOnce() throws Exception {
        CountingAlgorithmDownloader downloader = new CountingAlgorithmDownloader(tempDir);
        try (AlgorithmRepository repository = repository(downloader)) {
            GraphObservation observation = observeGraph(repository, "parameter-1");
            List<AlgorithmRepository.CachedAlgorithmGraph> leases = new ArrayList<>();
            leases.add(observation.graph());
            for (int index = 0; index < 31; index++) {
                leases.add(observation.graph().retain());
            }

            closeConcurrently(leases);

            assertTrue(observation.algorithm().isClosed());
            assertEquals(1, observation.algorithm().closeCallCount());
            assertNoRegisteredOwners(repository);
            assertTrue(downloader.stagedJars().stream().noneMatch(Files::exists));
            assertThrows(IllegalStateException.class, observation.graph()::retain);
        }
    }

    @Test
    void concurrentParentReleaseClosesTheirSharedNodeExactlyOnce() throws Exception {
        CanonicalSharedFactory.reset();
        AlgorithmMetadata firstMetadata = parameterlessMetadata("first", "s3://bucket/a-first.jar");
        AlgorithmMetadata secondMetadata = parameterlessMetadata("second", "s3://bucket/b-second.jar");
        SharedProviderDownloader downloader = new SharedProviderDownloader(
                tempDir,
                Map.of(
                        firstMetadata.algorithmId(), artifactDefinitions(firstMetadata, CanonicalSharedFactory.class),
                        secondMetadata.algorithmId(), artifactDefinitions(secondMetadata, OtherSharedFactory.class)));
        try (AlgorithmRepository repository = repository(downloader)) {
            SharedNodeLifecycleObservation observation = observeSharedNodeLifecycle(
                    repository, downloader, firstMetadata, secondMetadata);

            closeConcurrently(List.of(observation.firstGraph(), observation.secondGraph()));

            assertEquals(1, observation.firstRoot().closeCallCount());
            assertEquals(1, observation.secondRoot().closeCallCount());
            assertEquals(1, observation.sharedAlgorithm().closeCallCount());
            assertNoRegisteredOwners(repository);
            assertTrue(downloader.stagedJars().stream().noneMatch(Files::exists));
        }
    }

    @Test
    void sharesOneArtifactAcrossRetainedParameterGraphs() {
        CountingAlgorithmDownloader downloader = new CountingAlgorithmDownloader(tempDir);

        try (AlgorithmRepository repository = repository(downloader)) {
            SharedGraphObservation observation = observeSharedGraphs(repository);
            Path stagedJar = downloader.stagedJars().getFirst();

            assertEquals(1, downloader.jarDownloadCount());
            assertEquals(2, downloader.algorithmLoadCount());
            assertTrue(Files.exists(stagedJar));

            observation.firstGraph().close();

            assertTrue(observation.firstAlgorithm().isClosed());
            assertFalse(observation.secondAlgorithm().isClosed());
            assertTrue(Files.exists(stagedJar));

            observation.secondGraph().close();
            assertTrue(observation.secondAlgorithm().isClosed());
            assertFalse(Files.exists(stagedJar));
        }
    }

    @Test
    void independentlyReleasedGraphsCanCleanUpConcurrently() throws Exception {
        CountingAlgorithmDownloader downloader = new CountingAlgorithmDownloader(tempDir);

        try (AlgorithmRepository repository = repository(downloader)) {
            BlockedCleanupObservation observation = observeBlockedCleanup(repository);
            Path blockedJar = downloader.stagedJars().getFirst();
            Thread blockedCleanup = Thread.ofVirtual().start(observation.blockedGraph()::close);
            try {
                await(observation.blockedAlgorithm()::closeStarted);

                observation.otherGraph().close();

                assertTrue(observation.otherAlgorithm().isClosed());
                assertFalse(observation.blockedAlgorithm().isClosed());
                assertTrue(Files.exists(blockedJar));
            } finally {
                observation.blockedAlgorithm().releaseClose();
                blockedCleanup.join();
            }
            assertTrue(observation.blockedAlgorithm().isClosed());
            assertFalse(Files.exists(blockedJar));
        }
    }

    @Test
    void releasesManyDistinctParameterGraphsDeterministically() {
        CountingAlgorithmDownloader downloader = new CountingAlgorithmDownloader(tempDir);
        int parameterCount = 25;

        try (AlgorithmRepository repository = repository(downloader)) {
            List<AlgorithmRepository.CachedAlgorithmGraph> owners = new ArrayList<>();
            for (int index = 0; index < parameterCount; index++) {
                owners.add(acquire(repository, algorithmMetadata("parameter-" + index)));
            }
            assertEquals(1, downloader.jarDownloadCount());
            owners.forEach(AlgorithmRepository.CachedAlgorithmGraph::close);

            assertTrue(downloader.constructedAlgorithms().stream().allMatch(TestAlgorithm::isClosed));
            assertTrue(downloader.stagedJars().stream().noneMatch(Files::exists));
            assertNoRegisteredOwners(repository);
        }

        assertEquals(parameterCount, downloader.algorithmLoadCount());
        assertEquals(parameterCount, downloader.constructedAlgorithms().size());
    }

    @Test
    void releasesOldCodeAndSharedProvidersAcrossRepeatedVersionChanges() {
        List<AlgorithmMetadata> versions = new ArrayList<>();
        Map<AlgorithmId, Map<String, String>> definitions = new LinkedHashMap<>();
        for (int version = 1; version <= 10; version++) {
            AlgorithmMetadata metadata = new AlgorithmMetadata(
                    "root", String.valueOf(version), null, "s3://bucket/root-" + version + ".jar", null);
            versions.add(metadata);
            definitions.put(metadata.algorithmId(), artifactDefinitions(
                    metadata, String.valueOf(version), CanonicalSharedFactory.class));
        }
        SharedProviderDownloader downloader = new SharedProviderDownloader(tempDir, definitions);
        try (AlgorithmRepository repository = repository(downloader)) {
            AlgorithmRepository.CachedAlgorithmGraph current = acquire(repository, versions.getFirst());
            try {
                for (int index = 1; index < versions.size(); index++) {
                    AlgorithmRepository.CachedAlgorithmGraph previous = current;
                    TestAlgorithm oldRoot = assertInstanceOf(TestAlgorithm.class, previous.instance().algorithm());
                    CanonicalSharedAlgorithm oldShared = assertInstanceOf(
                            CanonicalSharedAlgorithm.class,
                            dependencies(previous.instance()).asMap().get("shared").algorithm());
                    ClassLoader oldLoader = previous.rootArtifactClassLoader();
                    current = acquire(repository, versions.get(index));
                    assertNotSame(oldLoader, current.rootArtifactClassLoader());
                    assertFalse(oldRoot.isClosed());
                    assertFalse(oldShared.isClosed());
                    assertTrue(Files.exists(downloader.stagedJars().get(index - 1)));

                    previous.close();

                    assertTrue(oldRoot.isClosed());
                    assertTrue(oldShared.isClosed());
                    assertEquals(1, oldShared.closeCallCount());
                    assertFalse(Files.exists(downloader.stagedJars().get(index - 1)));
                    assertNotNull(current.rootArtifactClassLoader().getResource("root-algorithm-definition.json"));
                }
            } finally {
                current.close();
            }
            assertNoRegisteredOwners(repository);
            assertTrue(downloader.stagedJars().stream().noneMatch(Files::exists));
        }
    }

    @Test
    void replacesARetiredGraphWhileItsCleanupIsBlocked() throws Exception {
        CountingAlgorithmDownloader downloader = new CountingAlgorithmDownloader(tempDir);
        try (AlgorithmRepository repository = repository(downloader)) {
            GraphObservation old = observeGraph(repository, "parameter-1");
            old.algorithm().blockClose();
            Thread cleanup = Thread.ofVirtual().start(old.graph()::close);
            try {
                await(old.algorithm()::closeStarted);
                try (AlgorithmRepository.CachedAlgorithmGraph replacement =
                        acquire(repository, algorithmMetadata("parameter-1"))) {
                    assertNotSame(old.algorithm(), replacement.instance().algorithm());
                    assertEquals(1, downloader.jarDownloadCount());
                    old.algorithm().releaseClose();
                    cleanup.join();

                    try (AlgorithmRepository.CachedAlgorithmGraph reused =
                            acquire(repository, algorithmMetadata("parameter-1"))) {
                        assertSame(replacement.instance(), reused.instance());
                        assertEquals(2, downloader.algorithmLoadCount());
                    }
                }
            } finally {
                old.algorithm().releaseClose();
                cleanup.join();
            }
            assertNoRegisteredOwners(repository);
            assertTrue(downloader.stagedJars().stream().noneMatch(Files::exists));
        }
    }

    @Test
    void evictsOwnersEvenWhenArtifactCleanupFails() throws Exception {
        CountingAlgorithmDownloader downloader = new CountingAlgorithmDownloader(tempDir);
        try (AlgorithmRepository repository = repository(downloader)) {
            GraphObservation old = observeGraph(repository, "parameter-1");
            Path stagedJar = downloader.stagedJars().getFirst();
            // The test downloader uses a placeholder JAR. Make its deletion fail deterministically.
            Files.delete(stagedJar);
            Files.createDirectory(stagedJar);
            Files.createFile(stagedJar.resolve("prevent-deletion"));

            RuntimeException failure = assertThrows(RuntimeException.class, old.graph()::close);
            assertInstanceOf(java.nio.file.DirectoryNotEmptyException.class, failure.getCause());
            assertTrue(old.algorithm().isClosed());
            assertNoRegisteredOwners(repository);

            try (AlgorithmRepository.CachedAlgorithmGraph replacement =
                    acquire(repository, algorithmMetadata("parameter-1"))) {
                assertNotSame(old.algorithm(), replacement.instance().algorithm());
                assertEquals(2, downloader.jarDownloadCount());
            }
            assertNoRegisteredOwners(repository);
        }
    }

    private static void assertNoRegisteredOwners(AlgorithmRepository repository) {
        // Resource closure alone would not detect retained graph/classloader references after churn.
        for (String name : List.of("liveGraphs", "liveSharedNodes", "artifactHolders")) {
            assertDoesNotThrow(() -> {
                var field = AlgorithmRepository.class.getDeclaredField(name);
                field.setAccessible(true);
                assertTrue(((Map<?, ?>) field.get(repository)).isEmpty(), name);
            });
        }
    }

    private static void closeConcurrently(List<AlgorithmRepository.CachedAlgorithmGraph> leases) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<?>> releases = new ArrayList<>();
            for (AlgorithmRepository.CachedAlgorithmGraph lease : leases) {
                // Race both distinct leases and duplicate closes of the same lease.
                for (int index = 0; index < 2; index++) {
                    releases.add(executor.submit(() -> {
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        lease.close();
                        return null;
                    }));
                }
            }
            start.countDown();
            for (Future<?> release : releases) {
                release.get(10, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsPreparationAfterTheRepositoryCloses() {
        CountingAlgorithmDownloader downloader = new CountingAlgorithmDownloader(tempDir);
        AlgorithmRepository repository = repository(downloader);

        repository.close();

        assertThrows(
                IllegalStateException.class,
                () -> acquire(repository, algorithmMetadata("parameter-1")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void closeDuringDownloadOrConstructionCleansUnpublishedResources(boolean blockDownload) throws Exception {
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch loadRelease = new CountDownLatch(1);
        CountingAlgorithmDownloader downloader = new CountingAlgorithmDownloader(tempDir) {
            @Override
            public DownloadedAlgorithmArtifact downloadAlgorithmArtifact(AlgorithmMetadata metadata) {
                DownloadedAlgorithmArtifact artifact = super.downloadAlgorithmArtifact(metadata);
                if (blockDownload) {
                    awaitRelease();
                }
                return artifact;
            }

            @Override
            public AlgorithmGraph<?> loadAlgorithmGraph(
                    AlgorithmMetadata metadata,
                    DownloadedAlgorithmArtifact artifact,
                    File parameterFile,
                    AlgorithmDependencies bindings,
                    AlgorithmGraphDependencies slots,
                    String rootProviderIdentity,
                    Map<AlgorithmId, AlgorithmArtifactProvider> providers,
                    SharedNodeInterner interner,
                    ExecutionContext context) {
                AlgorithmGraph<?> graph = super.loadAlgorithmGraph(
                        metadata, artifact, parameterFile, bindings, slots, rootProviderIdentity,
                        providers, interner, context);
                if (!blockDownload) {
                    awaitRelease();
                }
                return graph;
            }

            private void awaitRelease() {
                loadStarted.countDown();
                try {
                    assertTrue(loadRelease.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
        };
        AlgorithmRepository repository = repository(downloader);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> loading = executor.submit(() -> {
                try (var graph = acquire(repository, algorithmMetadata("parameter-1"))) {
                    graph.instance();
                }
            });
            assertTrue(loadStarted.await(10, TimeUnit.SECONDS));

            // Slow I/O and construction cannot hold the bookkeeping lock needed by close().
            executor.submit(repository::close).get(1, TimeUnit.SECONDS);
            loadRelease.countDown();

            ExecutionException failure = assertThrows(ExecutionException.class, () -> loading.get(10, TimeUnit.SECONDS));
            assertEquals("Algorithm repository is closed", failure.getCause().getMessage());
            assertNoRegisteredOwners(repository);
            assertTrue(downloader.stagedJars().stream().noneMatch(Files::exists));
            assertEquals(blockDownload ? 0 : 1, downloader.constructedAlgorithms().size());
            downloader.constructedAlgorithms().forEach(algorithm -> assertEquals(1, algorithm.closeCallCount()));
        } finally {
            loadRelease.countDown();
            executor.shutdownNow();
            repository.close();
        }
    }

    private static GraphObservation observeGraph(AlgorithmRepository repository, String parameterId) {
        AlgorithmRepository.CachedAlgorithmGraph graph = acquire(repository, algorithmMetadata(parameterId));
        return new GraphObservation(
                graph,
                assertInstanceOf(TestAlgorithm.class, graph.instance().algorithm()));
    }

    private static SharedGraphObservation observeSharedGraphs(AlgorithmRepository repository) {
        AlgorithmRepository.CachedAlgorithmGraph first = acquire(repository, algorithmMetadata("parameter-1"));
        AlgorithmRepository.CachedAlgorithmGraph second = acquire(repository, algorithmMetadata("parameter-2"));
        return new SharedGraphObservation(
                first,
                assertInstanceOf(TestAlgorithm.class, first.instance().algorithm()),
                second,
                assertInstanceOf(TestAlgorithm.class, second.instance().algorithm()));
    }

    private static SharedNodeLifecycleObservation observeSharedNodeLifecycle(
            AlgorithmRepository repository,
            SharedProviderDownloader downloader,
            AlgorithmMetadata firstMetadata,
            AlgorithmMetadata secondMetadata) {
        try (AlgorithmRepository.AlgorithmArtifactInspection firstInspection =
                        repository.inspectAlgorithm(firstMetadata);
                AlgorithmRepository.AlgorithmArtifactInspection secondInspection =
                        repository.inspectAlgorithm(secondMetadata);
                AlgorithmRepository.SharedArtifactCatalog sharedArtifacts = repository.sharedArtifactCatalog(
                        List.of(firstInspection, secondInspection))) {
            AlgorithmRepository.CachedAlgorithmGraph first = repository.acquireComposedAlgorithm(
                    firstInspection,
                    Map.of(),
                    sharedArtifacts,
                    ExecutionContext.realtime(InputSemantic.ONLINE));
            AlgorithmRepository.CachedAlgorithmGraph second = repository.acquireComposedAlgorithm(
                    secondInspection,
                    Map.of(),
                    sharedArtifacts,
                    ExecutionContext.realtime(InputSemantic.ONLINE));
            AlgorithmInstance<?> firstShared = dependencies(first.instance()).asMap().get("shared");
            AlgorithmInstance<?> secondShared = dependencies(second.instance()).asMap().get("shared");
            assertSame(firstShared, secondShared);
            return new SharedNodeLifecycleObservation(
                    first,
                    assertInstanceOf(TestAlgorithm.class, first.instance().algorithm()),
                    second,
                    assertInstanceOf(TestAlgorithm.class, second.instance().algorithm()),
                    assertInstanceOf(CanonicalSharedAlgorithm.class, firstShared.algorithm()),
                    downloader.stagedJars().getFirst());
        }
    }

    private static BlockedCleanupObservation observeBlockedCleanup(AlgorithmRepository repository) {
        AlgorithmRepository.CachedAlgorithmGraph blocked = acquire(repository, algorithmMetadata("parameter-1"));
        TestAlgorithm blockedAlgorithm = assertInstanceOf(TestAlgorithm.class, blocked.instance().algorithm());
        blockedAlgorithm.blockClose();
        AlgorithmRepository.CachedAlgorithmGraph other = acquire(repository, algorithmMetadata("parameter-2"));
        return new BlockedCleanupObservation(
                blocked,
                blockedAlgorithm,
                other,
                assertInstanceOf(TestAlgorithm.class, other.instance().algorithm()));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for graph cleanup");
    }

    private record GraphObservation(
            AlgorithmRepository.CachedAlgorithmGraph graph,
            TestAlgorithm algorithm) {
    }

    private record SharedGraphObservation(
            AlgorithmRepository.CachedAlgorithmGraph firstGraph,
            TestAlgorithm firstAlgorithm,
            AlgorithmRepository.CachedAlgorithmGraph secondGraph,
            TestAlgorithm secondAlgorithm) {
    }

    private record SharedNodeLifecycleObservation(
            AlgorithmRepository.CachedAlgorithmGraph firstGraph,
            TestAlgorithm firstRoot,
            AlgorithmRepository.CachedAlgorithmGraph secondGraph,
            TestAlgorithm secondRoot,
            CanonicalSharedAlgorithm sharedAlgorithm,
            Path sharedArtifact) {
    }

    private record BlockedCleanupObservation(
            AlgorithmRepository.CachedAlgorithmGraph blockedGraph,
            TestAlgorithm blockedAlgorithm,
            AlgorithmRepository.CachedAlgorithmGraph otherGraph,
            TestAlgorithm otherAlgorithm) {
    }

    private static AlgorithmMetadata algorithmMetadata(final String parameterId) {
        return algorithmMetadata(parameterId, "s3://bucket/test-algorithm.jar");
    }

    private static AlgorithmMetadata algorithmMetadata(final String parameterId, final String jarPath) {
        return algorithmMetadata(parameterId, jarPath, "s3://bucket/" + parameterId + ".zip");
    }

    private static AlgorithmMetadata algorithmMetadata(
            final String parameterId,
            final String jarPath,
            final String parameterPath) {
        return new AlgorithmMetadata(
                "test-algorithm",
                "1.0.0",
                parameterId,
                jarPath,
                parameterPath);
    }

    private static AlgorithmMetadata parameterlessAlgorithmMetadata() {
        return new AlgorithmMetadata(
                "test-algorithm",
                "1.0.0",
                null,
                "s3://bucket/test-algorithm.jar",
                null);
    }

    private static AlgorithmMetadata parameterlessMetadata(String algorithmName, String jarPath) {
        return new AlgorithmMetadata(algorithmName, "1", null, jarPath, null);
    }

    private static String parameterMetadata(String algorithmName, String algorithmVersion, String parameterId) {
        return parameterMetadataAt(
                algorithmName,
                algorithmVersion,
                parameterId,
                "2026-08-15",
                "2026-08-15T00:00:00Z");
    }

    private static String parameterMetadataAt(
            String algorithmName,
            String algorithmVersion,
            String parameterId,
            String lastTestTime,
            String ranAt) {
        return """
                {
                  "algorithm_name": "%s",
                  "algorithm_version": "%s",
                  "parameter_id": "%s",
                  "ran_at": "%s",
                  "last_test_time": "%s"
                }
                """.formatted(algorithmName, algorithmVersion, parameterId, ranAt, lastTestTime);
    }

    private static Map<String, String> parameterArchive(
            String rootName,
            String rootParameterId,
            String sharedParameterId,
            String sharedLastTestTime,
            String sharedRanAt,
            String sharedState) {
        return Map.of(
                rootName + "/algorithm-parameters.json", parameterMetadata(
                        rootName, "1", rootParameterId),
                "shared/algorithm-parameters.json", parameterMetadataAt(
                        "shared", "1", sharedParameterId, sharedLastTestTime, sharedRanAt),
                "shared/model.parameter", sharedState);
    }

    private static Map<String, String> artifactDefinitions(
            AlgorithmMetadata root,
            Class<?> sharedFactory) {
        return artifactDefinitions(root, "1", sharedFactory);
    }

    private static Map<String, String> artifactDefinitions(
            AlgorithmMetadata root,
            String sharedVersion,
            Class<?> sharedFactory) {
        return Map.of(
                root.algorithmName() + "-algorithm-definition.json",
                """
                        {
                          "algorithm_name": "%s",
                          "algorithm_version": "%s",
                          "algorithm_factory_classname": "%s",
                          "dependencies": {"shared@%s": {"scope": "shared"}}
                        }
                        """.formatted(
                                root.algorithmName(),
                                root.algorithmVersion(),
                                SharedConsumerFactory.class.getName(),
                                sharedVersion),
                "shared-algorithm-definition.json",
                """
                        {
                          "algorithm_name": "shared",
                          "algorithm_version": "%s",
                          "algorithm_factory_classname": "%s"
                        }
                        """.formatted(sharedVersion, sharedFactory.getName()));
    }

    private static Map<String, String> applicationBoundArtifactDefinitions(AlgorithmMetadata root) {
        return Map.of(
                root.algorithmName() + "-algorithm-definition.json",
                """
                        {
                          "algorithm_name": "%s",
                          "algorithm_version": "%s",
                          "algorithm_factory_classname": "%s",
                          "dependencies": {"encoder": {}}
                        }
                        """.formatted(
                                root.algorithmName(),
                                root.algorithmVersion(),
                                SharedConsumerFactory.class.getName()));
    }

    private static Map<String, String> applicationBoundSharedArtifactDefinitions(AlgorithmMetadata root) {
        return Map.of(
                root.algorithmName() + "-algorithm-definition.json",
                """
                        {
                          "algorithm_name": "%s",
                          "algorithm_version": "%s",
                          "algorithm_factory_classname": "%s",
                          "dependencies": {"shared@1": {"scope": "shared"}}
                        }
                        """.formatted(
                                root.algorithmName(),
                                root.algorithmVersion(),
                                SharedConsumerFactory.class.getName()),
                "shared-algorithm-definition.json",
                """
                        {
                          "algorithm_name": "shared",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "%s",
                          "dependencies": {"encoder": {}}
                        }
                        """.formatted(NestedSharedFactory.class.getName()));
    }

    private static Map<String, String> nestedSharedArtifactDefinitions(AlgorithmMetadata root) {
        return Map.of(
                root.algorithmName() + "-algorithm-definition.json",
                """
                        {
                          "algorithm_name": "%s",
                          "algorithm_version": "%s",
                          "algorithm_factory_classname": "%s",
                          "dependencies": {"shared@1": {"scope": "shared"}}
                        }
                        """.formatted(
                                root.algorithmName(),
                                root.algorithmVersion(),
                                SharedConsumerFactory.class.getName()),
                "shared-algorithm-definition.json",
                """
                        {
                          "algorithm_name": "shared",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "%s",
                          "dependencies": {"inner@1": {"scope": "shared"}}
                        }
                        """.formatted(NestedSharedFactory.class.getName()),
                "inner-algorithm-definition.json",
                """
                        {
                          "algorithm_name": "inner",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "%s"
                        }
                        """.formatted(CanonicalSharedFactory.class.getName()));
    }

    private static Map<String, String> branchingSharedArtifactDefinitions(
            AlgorithmMetadata root,
            boolean includeOuterA) {
        String rootDependencies = includeOuterA
                ? """
                        {"outer-a@1": {"scope": "shared"}, "outer-b@1": {"scope": "shared"}}
                        """
                : """
                        {"outer-b@1": {"scope": "shared"}}
                        """;
        LinkedHashMap<String, String> definitions = new LinkedHashMap<>();
        definitions.put(
                root.algorithmName() + "-algorithm-definition.json",
                """
                        {
                          "algorithm_name": "%s",
                          "algorithm_version": "%s",
                          "algorithm_factory_classname": "%s",
                          "dependencies": %s
                        }
                        """.formatted(
                                root.algorithmName(),
                                root.algorithmVersion(),
                                SharedConsumerFactory.class.getName(),
                                rootDependencies));
        if (includeOuterA) {
            definitions.put(
                    "outer-a-algorithm-definition.json",
                    nestedSharedParentDefinition("outer-a"));
        }
        definitions.put(
                "outer-b-algorithm-definition.json",
                nestedSharedParentDefinition("outer-b"));
        definitions.put(
                "inner-algorithm-definition.json",
                """
                        {
                          "algorithm_name": "inner",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "%s"
                        }
                        """.formatted(CanonicalSharedFactory.class.getName()));
        return Map.copyOf(definitions);
    }

    private static String nestedSharedParentDefinition(String name) {
        return """
                {
                  "algorithm_name": "%s",
                  "algorithm_version": "1",
                  "algorithm_factory_classname": "%s",
                  "dependencies": {"inner@1": {"scope": "shared"}}
                }
                """.formatted(name, NestedSharedFactory.class.getName());
    }

    private static Map<String, String> nestedParameterizedSharedArtifactDefinitions(AlgorithmMetadata root) {
        Map<String, String> definitions = new LinkedHashMap<>(nestedSharedArtifactDefinitions(root));
        definitions.put(
                "inner-algorithm-definition.json",
                """
                        {
                          "algorithm_name": "inner",
                          "algorithm_version": "1",
                          "algorithm_factory_classname": "%s"
                        }
                        """.formatted(ParameterizedCanonicalSharedFactory.class.getName()));
        return Map.copyOf(definitions);
    }

    private static AlgorithmRepository repository(AlgorithmDownloader downloader) {
        return new AlgorithmRepository(downloader, null, AlgorithmDependencies.empty());
    }

    private static io.micrometer.core.instrument.Gauge parameterAgeGauge(SimpleMeterRegistry meterRegistry) {
        return meterRegistry.find("ems.algorithm_parameter.age")
                .tags(
                        "AlgorithmName", "test-algorithm@1.0.0",
                        "AlgorithmParameterId", "parameter-1")
                .gauge();
    }

    private static AlgorithmRepository.CachedAlgorithmGraph acquire(
            AlgorithmRepository repository,
            AlgorithmMetadata metadata) {
        try (AlgorithmRepository.AlgorithmArtifactInspection inspection =
                        repository.inspectAlgorithm(metadata);
                AlgorithmRepository.SharedArtifactCatalog sharedArtifacts =
                        repository.sharedArtifactCatalog(List.of(inspection))) {
            return repository.acquireComposedAlgorithm(
                    inspection,
                    Map.of(),
                    sharedArtifacts,
                    ExecutionContext.realtime(InputSemantic.ONLINE));
        }
    }

    private static AlgorithmDependencies dependencies(AlgorithmInstance<?> instance) {
        Algorithm algorithm = instance.algorithm();
        if (algorithm instanceof TestAlgorithm testAlgorithm) {
            return testAlgorithm.dependencies();
        }
        if (algorithm instanceof NestedSharedAlgorithm nestedSharedAlgorithm) {
            return nestedSharedAlgorithm.dependencies();
        }
        throw new IllegalArgumentException(
                "Test algorithm does not retain constructed dependencies: " + algorithm.getClass().getName());
    }

    private static AlgorithmDefinition definition(final AlgorithmMetadata metadata) {
        return new AlgorithmDefinition(
                JsonNodeFactory.instance.objectNode(),
                metadata.algorithmId(),
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                TestAlgorithmFactory.class.getName(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static final class SharedProviderDownloader extends AlgorithmDownloader {
        private final Path artifactDirectory;
        private final Map<AlgorithmId, Map<String, String>> definitions;
        private final List<Path> stagedJars = new ArrayList<>();
        private final List<Path> stagedParameters = new ArrayList<>();
        private final AtomicInteger parameterDownloads = new AtomicInteger();
        private int artifactCount;

        private SharedProviderDownloader(
                Path artifactDirectory,
                Map<AlgorithmId, Map<String, String>> definitions) {
            this(artifactDirectory, definitions, Map.of());
        }

        private SharedProviderDownloader(
                Path artifactDirectory,
                Map<AlgorithmId, Map<String, String>> definitions,
                Map<AlgorithmId, Map<String, String>> parameterEntries) {
            this(
                    artifactDirectory,
                    definitions,
                    algorithm -> parameterEntries.get(algorithm.algorithmId()));
        }

        private SharedProviderDownloader(
                Path artifactDirectory,
                Map<AlgorithmId, Map<String, String>> definitions,
                Function<AlgorithmMetadata, Map<String, String>> parameterEntries) {
            super(
                    parameterDownloadClient(parameterEntries),
                    artifactDirectory,
                    AlgorithmRepositoryTest.class.getClassLoader(),
                    new AlgorithmDownloader.Options(
                            InputSemantic.ONLINE,
                            false,
                            false,
                            Optional.empty()));
            this.artifactDirectory = artifactDirectory;
            this.definitions = Map.copyOf(definitions);
        }

        @Override
        public DownloadedAlgorithmArtifact downloadAlgorithmArtifact(AlgorithmMetadata metadata) {
            Map<String, String> artifactDefinitions = definitions.get(metadata.algorithmId());
            if (artifactDefinitions == null) {
                throw new IllegalArgumentException("No test artifact for " + metadata.algorithmId());
            }
            try {
                Files.createDirectories(artifactDirectory);
                Path jar = artifactDirectory.resolve("shared-provider-" + (++artifactCount) + ".jar");
                try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
                    for (Map.Entry<String, String> definition : artifactDefinitions.entrySet()) {
                        output.putNextEntry(new JarEntry(definition.getKey()));
                        output.write(definition.getValue().getBytes(StandardCharsets.UTF_8));
                        output.closeEntry();
                    }
                }
                stagedJars.add(jar);
                return DownloadedAlgorithmArtifacts.create(
                        new AlgorithmInstanceFactory(
                                jar.toFile(),
                                AlgorithmRepositoryTest.class.getClassLoader(),
                                new AlgorithmInstanceFactory.Options(
                                        InputSemantic.ONLINE,
                                        false,
                                        false,
                                        Optional.empty())),
                        jar);
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        }

        @Override
        public DownloadedAlgorithmParameters downloadAlgorithmParameters(AlgorithmMetadata metadata) {
            parameterDownloads.incrementAndGet();
            DownloadedAlgorithmParameters parameters =
                    super.downloadAlgorithmParameters(metadata);
            stagedParameters.add(parameters.file().toPath());
            return parameters;
        }

        private int parameterDownloadCount() {
            return parameterDownloads.get();
        }

        private List<Path> stagedJars() {
            return List.copyOf(stagedJars);
        }

        private List<Path> stagedParameters() {
            return List.copyOf(stagedParameters);
        }

        private static AlgorithmDownloadClient parameterDownloadClient(
                Function<AlgorithmMetadata, Map<String, String>> parameterEntries) {
            return new AlgorithmDownloadClient() {
                @Override
                public void downloadAlgorithmJar(AlgorithmMetadata algorithm, Path destination) {
                    throw new AssertionError("Artifact download is overridden by the test downloader");
                }

                @Override
                public void downloadAlgorithmParameter(AlgorithmMetadata algorithm, Path destination) {
                    Map<String, String> archiveEntries = parameterEntries.apply(algorithm);
                    if (archiveEntries == null) {
                        throw new AssertionError("No test parameter archive for " + algorithm.algorithmId());
                    }
                    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(destination))) {
                        for (var entry : archiveEntries.entrySet()) {
                            output.putNextEntry(new ZipEntry(entry.getKey()));
                            output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                            output.closeEntry();
                        }
                    } catch (IOException error) {
                        throw new RuntimeException(error);
                    }
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static class CountingAlgorithmDownloader extends AlgorithmDownloader {
        private final Path artifactDirectory;
        private final boolean omitParameterMetadata;
        private final AtomicInteger jarDownloads = new AtomicInteger();
        private final AtomicInteger algorithmLoads = new AtomicInteger();
        private final List<Path> stagedJars = new ArrayList<>();
        private final List<TestAlgorithm> constructedAlgorithms = new ArrayList<>();

        private CountingAlgorithmDownloader(final Path artifactDirectory) {
            this(artifactDirectory, false);
        }

        private CountingAlgorithmDownloader(
                final Path artifactDirectory,
                final boolean omitParameterMetadata) {
            super(
                    parameterDownloadClient(),
                    artifactDirectory,
                    AlgorithmRepositoryTest.class.getClassLoader(),
                    new AlgorithmDownloader.Options(
                            InputSemantic.ONLINE,
                            false,
                            false,
                            Optional.empty()));
            this.artifactDirectory = artifactDirectory;
            this.omitParameterMetadata = omitParameterMetadata;
        }

        @Override
        public AlgorithmDefinition readAlgorithmDefinition(
                DownloadedAlgorithmArtifact artifact,
                String algorithmName) {
            return definition(parameterlessAlgorithmMetadata());
        }

        @Override
        public DownloadedAlgorithmArtifact downloadAlgorithmArtifact(final AlgorithmMetadata algorithmMetadata) {
            int downloadNumber = jarDownloads.incrementAndGet();
            Path stagedJar = artifactDirectory.resolve("algorithm-" + downloadNumber + ".jar");
            try {
                Files.writeString(stagedJar, "jar");
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
            stagedJars.add(stagedJar);
            return DownloadedAlgorithmArtifacts.create(
                    new AlgorithmInstanceFactory(
                            AlgorithmRepositoryTest.class.getClassLoader(),
                            new AlgorithmInstanceFactory.Options(
                                    InputSemantic.ONLINE,
                                    false,
                                    false,
                                    Optional.empty())),
                    stagedJar);
        }

        @Override
        public AlgorithmGraph<?> loadAlgorithmGraph(
                final AlgorithmMetadata algorithmMetadata,
                final DownloadedAlgorithmArtifact artifact,
                final File parameterFile,
                final AlgorithmDependencies dependencyOverrides,
                final AlgorithmGraphDependencies slotBindings,
                final String rootProviderIdentity,
                final Map<com.hotvect.api.algodefinition.AlgorithmId, AlgorithmArtifactProvider> sharedProviders,
                final SharedNodeInterner sharedNodeInterner,
                ExecutionContext executionContext) {
            algorithmLoads.incrementAndGet();
            AlgorithmGraph<?> graph = omitParameterMetadata
                    ? new AlgorithmInstanceFactory(
                            AlgorithmRepositoryTest.class.getClassLoader(),
                            new AlgorithmInstanceFactory.Options(
                                    InputSemantic.ONLINE,
                                    false,
                                    false,
                                    Optional.empty()))
                            .loadGraph(
                                    definition(algorithmMetadata),
                                    null,
                                    dependencyOverrides,
                                    ExecutionContext.realtime(InputSemantic.ONLINE))
                    : super.loadAlgorithmGraph(
                            algorithmMetadata,
                            artifact,
                            parameterFile,
                            dependencyOverrides,
                            slotBindings,
                            rootProviderIdentity,
                            sharedProviders,
                            sharedNodeInterner,
                            executionContext);
            constructedAlgorithms.add(assertInstanceOf(TestAlgorithm.class, graph.algorithm()));
            return graph;
        }

        private static AlgorithmDownloadClient parameterDownloadClient() {
            return new AlgorithmDownloadClient() {
                @Override
                public void downloadAlgorithmJar(AlgorithmMetadata algorithm, Path destination) {
                    throw new AssertionError("Artifact download is overridden by the test downloader");
                }

                @Override
                public void downloadAlgorithmParameter(AlgorithmMetadata algorithm, Path destination) {
                    String metadata = """
                            {"algorithm_name":"%s","algorithm_version":"%s",\
                            "parameter_id":"%s","ran_at":"2026-01-01T00:00:00Z"}
                            """.formatted(
                                    algorithm.algorithmName(),
                                    algorithm.algorithmVersion(),
                                    algorithm.latestAlgorithmParameter());
                    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(destination))) {
                        output.putNextEntry(new ZipEntry(algorithm.algorithmName() + "/algorithm-parameters.json"));
                        output.write(metadata.getBytes(StandardCharsets.UTF_8));
                        output.closeEntry();
                    } catch (IOException error) {
                        throw new RuntimeException(error);
                    }
                }

                @Override
                public void close() {
                }
            };
        }

        private int jarDownloadCount() {
            return jarDownloads.get();
        }

        private int algorithmLoadCount() {
            return algorithmLoads.get();
        }

        private List<Path> stagedJars() {
            return List.copyOf(stagedJars);
        }

        private List<TestAlgorithm> constructedAlgorithms() {
            return List.copyOf(constructedAlgorithms);
        }
    }

    public static final class TestAlgorithmFactory implements SimpleAlgorithmFactory<TestAlgorithm> {
        @Override
        public TestAlgorithm apply(final Optional<JsonNode> hyperparameter) {
            return new TestAlgorithm();
        }
    }

    public static final class SharedConsumerFactory implements CompositeAlgorithmFactory<TestAlgorithm> {
        @Override
        public TestAlgorithm create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<JsonNode> hyperparameters,
                Map<String, java.io.InputStream> parameters,
                AlgorithmDependencies dependencies) {
            return new TestAlgorithm(dependencies);
        }
    }

    public static final class NestedSharedFactory implements CompositeAlgorithmFactory<Ranker<String, String>> {
        private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

        @Override
        public Ranker<String, String> create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<JsonNode> hyperparameters,
                Map<String, java.io.InputStream> parameters,
                AlgorithmDependencies dependencies) {
            CONSTRUCTIONS.incrementAndGet();
            return new NestedSharedAlgorithm(dependencies);
        }

        private static void reset() {
            CONSTRUCTIONS.set(0);
        }

        private static int constructionCount() {
            return CONSTRUCTIONS.get();
        }
    }

    public static final class NestedSharedAlgorithm implements Ranker<String, String> {
        private final AlgorithmDependencies dependencies;

        private NestedSharedAlgorithm(AlgorithmDependencies dependencies) {
            this.dependencies = Objects.requireNonNull(dependencies, "dependencies must not be null");
        }

        private AlgorithmDependencies dependencies() {
            return dependencies;
        }

        @Override
        public RankingResponse<String> rank(RankingRequest<String, String> rankingRequest) {
            return null;
        }
    }

    public static final class CanonicalSharedFactory implements SimpleAlgorithmFactory<Ranker<String, String>> {
        private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

        @Override
        public Ranker<String, String> apply(Optional<JsonNode> hyperparameter) {
            CONSTRUCTIONS.incrementAndGet();
            return new CanonicalSharedAlgorithm();
        }

        private static void reset() {
            CONSTRUCTIONS.set(0);
        }

        private static int constructionCount() {
            return CONSTRUCTIONS.get();
        }
    }

    public static final class ParameterizedCanonicalSharedFactory
            implements CompositeAlgorithmFactory<ParameterizedSharedAlgorithm> {
        @Override
        public ParameterizedSharedAlgorithm create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<JsonNode> hyperparameters,
                Map<String, java.io.InputStream> parameters,
                AlgorithmDependencies dependencies) {
            try {
                return new ParameterizedSharedAlgorithm(new String(
                        parameters.get("model.parameter").readAllBytes(),
                        StandardCharsets.UTF_8));
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        }
    }

    public record ParameterizedSharedAlgorithm(String parameter) implements Algorithm {
    }

    public static final class OtherSharedFactory implements SimpleAlgorithmFactory<Ranker<String, String>> {
        @Override
        public Ranker<String, String> apply(Optional<JsonNode> hyperparameter) {
            return new OtherSharedAlgorithm();
        }
    }

    public static final class CanonicalSharedAlgorithm implements Ranker<String, String> {
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public RankingResponse<String> rank(RankingRequest<String, String> rankingRequest) {
            return null;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        private boolean isClosed() {
            return closeCalls.get() > 0;
        }

        private int closeCallCount() {
            return closeCalls.get();
        }
    }

    public static final class OtherSharedAlgorithm implements Ranker<String, String> {
        @Override
        public RankingResponse<String> rank(RankingRequest<String, String> rankingRequest) {
            return null;
        }
    }

    private static final class TestAlgorithm implements Algorithm {
        private final AlgorithmDependencies dependencies;
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final AtomicReference<CountDownLatch> closeBlocker = new AtomicReference<>();
        private final CountDownLatch closeFinished = new CountDownLatch(1);

        private TestAlgorithm() {
            this(AlgorithmDependencies.empty());
        }

        private TestAlgorithm(AlgorithmDependencies dependencies) {
            this.dependencies = Objects.requireNonNull(dependencies, "dependencies must not be null");
        }

        private AlgorithmDependencies dependencies() {
            return dependencies;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closeStarted.countDown();
            CountDownLatch blocker = closeBlocker.get();
            if (blocker != null) {
                try {
                    blocker.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(error);
                }
            }
            closeFinished.countDown();
        }

        private void blockClose() {
            closeBlocker.set(new CountDownLatch(1));
        }

        private void releaseClose() {
            closeBlocker.get().countDown();
        }

        private boolean closeStarted() {
            return closeStarted.getCount() == 0;
        }

        private boolean isClosed() {
            return closeFinished.getCount() == 0;
        }

        private int closeCallCount() {
            return closeCalls.get();
        }
    }
}
