package com.hotvect.onlineutils.serving;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.hash.Hashing;
import com.google.common.reflect.TypeToken;
import com.hotvect.api.algodefinition.AlgorithmDependencies;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.HyperparameterizedAlgorithmId;
import com.hotvect.api.algodefinition.ParameterizedAlgorithmId;
import com.hotvect.api.algodefinition.common.CompositeAlgorithmFactory;
import com.hotvect.api.algodefinition.ranking.SimpleRankerFactory;
import com.hotvect.api.algodefinition.storage.LocalStateStorage;
import com.hotvect.api.algodefinition.topk.SimpleTopKFactory;
import com.hotvect.api.algorithms.Ranker;
import com.hotvect.api.algorithms.TopK;
import com.hotvect.api.data.ranking.RankingRequest;
import com.hotvect.api.data.ranking.RankingResponse;
import com.hotvect.api.data.topk.TopKRequest;
import com.hotvect.api.data.topk.TopKResponse;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.onlineutils.experimentmanagement.algodownload.AlgorithmDownloader;
import com.hotvect.onlineutils.experimentmanagement.algodownload.DownloadedAlgorithmArtifact;
import com.hotvect.onlineutils.experimentmanagement.algodownload.DownloadedAlgorithmArtifacts;
import com.hotvect.onlineutils.experimentmanagement.httpclient.ExperimentManagementServiceClient;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.experimentmanagement.models.Experiment;
import com.hotvect.onlineutils.experimentmanagement.models.Shard;
import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import com.hotvect.onlineutils.experimentmanagement.models.Variant;
import com.hotvect.onlineutils.hotdeploy.AlgorithmArtifactProvider;
import com.hotvect.onlineutils.hotdeploy.AlgorithmGraph;
import com.hotvect.onlineutils.hotdeploy.AlgorithmGraphDependencies;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.hotdeploy.SharedNodeInterner;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveSlotGraphResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsSubMillisecondRefreshPeriodDuringConstruction() throws Exception {
        ArtifactFixture artifacts = artifacts();
        DefinitionBackedDownloader downloader = new DefinitionBackedDownloader(tempDir, artifacts.definitions());
        MutableEmsClient emsClient = multiRootEmsClient(artifacts);
        AlgorithmRepository repository = repository(downloader);

        try {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> new LiveSlotGraphResolver(
                            Map.of(
                                    "root-slot", servingSlot("root-slot", "search"),
                                    "other-root", servingSlot("other-root", "homepage")),
                            repository,
                            emsClient,
                            Duration.ofNanos(999_999)));

            assertEquals("refreshPeriod must be at least one millisecond", error.getMessage());
            assertTrue(emsClient.requestedSlots().isEmpty());
            assertEquals(0, downloader.graphLoadCount());
        } finally {
            try {
                repository.close();
            } finally {
                emsClient.close();
            }
        }
    }

    @Test
    void invokingUnpreparedResolverSignalsIllegalStateException() throws Exception {
        ArtifactFixture artifacts = artifacts();
        MutableEmsClient emsClient = multiRootEmsClient(artifacts);
        AlgorithmRepository repository = repository(
                new DefinitionBackedDownloader(tempDir, artifacts.definitions()));
        LiveSlotGraphResolver resolver = multiRootResolver(repository, emsClient);

        try {
            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> invokeRanker(resolver, "root-slot", "search", "customer-1"));

            assertEquals("Serving runtime is not ready or is closed", error.getMessage());
        } finally {
            close(resolver, repository, emsClient);
        }
    }

    @Test
    void discoversNestedSlotsAndPublishesAllConfiguredRootsAsOneGeneration() throws Exception {
        ArtifactFixture artifacts = artifacts();
        MutableEmsClient emsClient = multiRootEmsClient(artifacts);
        DefinitionBackedDownloader downloader = new DefinitionBackedDownloader(tempDir, artifacts.definitions());
        AlgorithmRepository repository = repository(downloader);
        LiveSlotGraphResolver resolver = multiRootResolver(repository, emsClient);

        try {
            resolver.refreshNow();

            ServingRuntimeStatus status = resolver.status();
            assertTrue(status.ready());
            assertEquals(1, status.generation());
            assertEquals(Set.of("other-root", "policy-slot", "root-slot"), status.assignments().keySet());
            assertTrue(status.assignments().get("root-slot").configuredRoot());
            assertTrue(status.assignments().get("other-root").configuredRoot());
            assertFalse(status.assignments().get("policy-slot").configuredRoot());
            assertEquals(Set.of("other-root", "root-slot", "policy-slot"), Set.copyOf(emsClient.requestedSlots()));

            int graphLoadCount = downloader.graphLoadCount();
            resolver.refreshNow();

            assertEquals(graphLoadCount, downloader.graphLoadCount());
            assertEquals(status.generation() + 1, resolver.status().generation());
        } finally {
            close(resolver, repository, emsClient);
        }
    }

    @Test
    void failedNestedEmsReadRetainsThePreviousCompleteGeneration() throws Exception {
        ArtifactFixture artifacts = artifacts();
        MutableEmsClient emsClient = multiRootEmsClient(artifacts);
        AlgorithmRepository repository = repository(
                new DefinitionBackedDownloader(tempDir, artifacts.definitions()));
        LiveSlotGraphResolver resolver = multiRootResolver(repository, emsClient);

        try {
            resolver.refreshNow();
            ServingRuntimeStatus active = resolver.status();
            emsClient.failSlot("policy-slot");

            assertThrows(IOException.class, resolver::refreshNow);

            ServingRuntimeStatus rejected = resolver.status();
            assertEquals(active.generation(), rejected.generation());
            assertEquals(active.activatedAt(), rejected.activatedAt());
            assertEquals(active.assignments(), rejected.assignments());
            assertNotNull(rejected.lastFailure());
            assertEquals("EMS read failed", rejected.lastFailure().summary());
            assertTrue(invokeRanker(resolver, "root-slot", "search", "customer-1").result());
            assertTrue(invokeRanker(resolver, "other-root", "homepage", "customer-1").result());
        } finally {
            close(resolver, repository, emsClient);
        }
    }

    @Test
    void failedGraphPreparationRetainsThePreviousCompleteGeneration() throws Exception {
        ArtifactFixture artifacts = artifacts();
        MutableEmsClient emsClient = multiRootEmsClient(artifacts);
        AlgorithmRepository repository = repository(
                new DefinitionBackedDownloader(tempDir, artifacts.definitions()));
        LiveSlotGraphResolver resolver = multiRootResolver(repository, emsClient);

        try {
            resolver.refreshNow();
            ServingRuntimeStatus active = resolver.status();
            emsClient.setSlot(
                    "root-slot",
                    rootSlot(metadata("missing-root")));

            assertThrows(RuntimeException.class, resolver::refreshNow);

            ServingRuntimeStatus rejected = resolver.status();
            assertEquals(active.generation(), rejected.generation());
            assertEquals(active.activatedAt(), rejected.activatedAt());
            assertEquals(active.assignments(), rejected.assignments());
            assertNotNull(rejected.lastFailure());
            assertEquals("Snapshot validation failed", rejected.lastFailure().summary());
            assertTrue(invokeRanker(resolver, "root-slot", "search", "customer-1").result());
        } finally {
            close(resolver, repository, emsClient);
        }
    }

    @Test
    void contractMismatchRejectsRefreshAndRetainsThePreviousGeneration() throws Exception {
        ArtifactFixture artifacts = artifacts();
        MutableEmsClient emsClient = new MutableEmsClient(Map.of(
                "root-slot", rootSlot(artifacts.root()),
                "policy-slot", policySlot(artifacts.childA())));
        AlgorithmRepository repository = repository(
                new DefinitionBackedDownloader(tempDir, artifacts.definitions()));
        LiveSlotGraphResolver resolver = new LiveSlotGraphResolver(
                Map.of("root-slot", servingSlot("root-slot", "search")),
                repository,
                emsClient,
                Duration.ofMinutes(1));

        try {
            resolver.refreshNow();
            ServingRuntimeStatus active = resolver.status();
            emsClient.setSlot("root-slot", rootSlot(artifacts.badRoot()));

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    resolver::refreshNow);

            assertTrue(failure.getMessage().contains("is incompatible with serving slot root-slot"));
            ServingRuntimeStatus rejected = resolver.status();
            assertEquals(active.generation(), rejected.generation());
            assertEquals(active.activatedAt(), rejected.activatedAt());
            assertEquals(active.assignments(), rejected.assignments());
            assertNotNull(rejected.lastFailure());
            assertEquals("Snapshot validation failed", rejected.lastFailure().summary());
            assertTrue(invokeRanker(resolver, "root-slot", "search", "customer-1").result());
        } finally {
            close(resolver, repository, emsClient);
        }
    }

    @Test
    void allows64CompleteCompositionsForOneRoot() throws Exception {
        WideFixture fixture = wideFixture(1, 6);
        DefinitionBackedDownloader downloader = new DefinitionBackedDownloader(tempDir, fixture.definitions());
        MutableEmsClient emsClient = new MutableEmsClient(fixture.emsSlots());
        AlgorithmRepository repository = repository(downloader);
        LiveSlotGraphResolver resolver = new LiveSlotGraphResolver(
                fixture.servingSlots(),
                repository,
                emsClient,
                Duration.ofMinutes(1));

        try {
            resolver.refreshNow();

            assertTrue(resolver.status().ready());
            assertTrue(invokeRanker(resolver, "root-0", "touchpoint-0", "customer-1").result());
            assertEquals(66, downloader.graphLoadCount());
        } finally {
            close(resolver, repository, emsClient);
        }
    }

    @Test
    void rejectsMoreThan64CompleteCompositionsForOneRootBeforeGraphConstruction() throws Exception {
        WideFixture fixture = wideFixture(1, 7);
        DefinitionBackedDownloader downloader = new DefinitionBackedDownloader(tempDir, fixture.definitions());
        MutableEmsClient emsClient = new MutableEmsClient(fixture.emsSlots());
        AlgorithmRepository repository = repository(downloader);
        LiveSlotGraphResolver resolver = new LiveSlotGraphResolver(
                fixture.servingSlots(),
                repository,
                emsClient,
                Duration.ofMinutes(1));

        try {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, resolver::refreshNow);

            assertTrue(failure.getMessage().contains("root-0 exceeds the limit of 64"));
            assertTrue(failure.getMessage().contains("at least 65 observed"));
            assertTrue(failure.getMessage().contains("policy-0=2"));
            assertEquals(0, downloader.graphLoadCount());
            assertFalse(resolver.status().ready());
        } finally {
            close(resolver, repository, emsClient);
        }
    }

    @Test
    void rejectsMoreThan256RuntimeCompositionsBeforeGraphConstruction() throws Exception {
        WideFixture fixture = wideFixture(5, 6);
        DefinitionBackedDownloader downloader = new DefinitionBackedDownloader(tempDir, fixture.definitions());
        MutableEmsClient emsClient = new MutableEmsClient(fixture.emsSlots());
        AlgorithmRepository repository = repository(downloader);
        LiveSlotGraphResolver resolver = new LiveSlotGraphResolver(
                fixture.servingSlots(),
                repository,
                emsClient,
                Duration.ofMinutes(1));

        try {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, resolver::refreshNow);

            assertTrue(failure.getMessage().contains("exceeds the limit of 256 complete root compositions"));
            assertTrue(failure.getMessage().contains("at least 320 observed"));
            assertTrue(failure.getMessage().contains("root-4=64"));
            assertEquals(0, downloader.graphLoadCount());
            assertFalse(resolver.status().ready());
        } finally {
            close(resolver, repository, emsClient);
        }
    }

    @Test
    void manualRefreshReplacesTheCompleteGraphInOneGeneration() throws Exception {
        ArtifactFixture artifacts = artifacts();
        MutableEmsClient emsClient = multiRootEmsClient(artifacts);
        AlgorithmRepository repository = repository(
                new DefinitionBackedDownloader(tempDir, artifacts.definitions()));
        LiveSlotGraphResolver resolver = multiRootResolver(repository, emsClient);

        try {
            resolver.refreshNow();
            ServingRuntimeStatus first = resolver.status();
            emsClient.clearRequestedSlots();
            emsClient.setSlot("root-slot", rootSlot(2, artifacts.root()));

            resolver.refreshNow();

            ServingRuntimeStatus second = resolver.status();
            assertEquals(first.generation() + 1, second.generation());
            assertEquals("2", second.assignments().get("root-slot").defaultVariantId());
            assertEquals(Set.of("other-root", "root-slot", "policy-slot"), Set.copyOf(emsClient.requestedSlots()));
            assertEquals(
                    "2",
                    rootVariantId(invokeRanker(resolver, "root-slot", "search", "customer-1")));
        } finally {
            close(resolver, repository, emsClient);
        }
    }

    @Test
    void refreshBuildDoesNotBlockInvocationAndPublishesOnlyTheCompleteCandidate() throws Exception {
        ArtifactFixture artifacts = artifacts();
        MutableEmsClient emsClient = multiRootEmsClient(artifacts);
        AlgorithmRepository repository = repository(
                new DefinitionBackedDownloader(tempDir, artifacts.definitions()));
        LiveSlotGraphResolver resolver = multiRootResolver(repository, emsClient);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            resolver.refreshNow();
            emsClient.setSlot("root-slot", rootSlot(2, artifacts.root()));
            emsClient.blockNextRead("root-slot");

            Future<?> refresh = executor.submit(() -> {
                resolver.refreshNow();
                return null;
            });
            assertTrue(emsClient.awaitBlockedRead());

            Future<AlgorithmExecution<Boolean>> invocation = executor.submit(
                    () -> invokeRanker(resolver, "root-slot", "search", "customer-1"));
            try {
                assertEquals("1", rootVariantId(invocation.get(1, TimeUnit.SECONDS)));
            } finally {
                emsClient.releaseBlockedRead();
            }

            refresh.get(10, TimeUnit.SECONDS);
            assertEquals(
                    "2",
                    rootVariantId(invokeRanker(resolver, "root-slot", "search", "customer-1")));
        } finally {
            executor.shutdownNow();
            close(resolver, repository, emsClient);
        }
    }

    @Test
    void concurrentRefreshFailsFastWithoutInterruptingTheActiveRefresh() throws Exception {
        ArtifactFixture artifacts = artifacts();
        MutableEmsClient emsClient = multiRootEmsClient(artifacts);
        AlgorithmRepository repository = repository(
                new DefinitionBackedDownloader(tempDir, artifacts.definitions()));
        LiveSlotGraphResolver resolver = multiRootResolver(repository, emsClient);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            resolver.refreshNow();
            emsClient.blockNextRead("root-slot");
            Future<?> activeRefresh = executor.submit(() -> {
                resolver.refreshNow();
                return null;
            });
            assertTrue(emsClient.awaitBlockedRead());

            IllegalStateException failure = assertThrows(IllegalStateException.class, resolver::refreshNow);
            assertEquals("A live serving graph refresh is already in progress", failure.getMessage());

            emsClient.releaseBlockedRead();
            activeRefresh.get(10, TimeUnit.SECONDS);
            assertEquals(2, resolver.status().generation());
        } finally {
            emsClient.releaseBlockedRead();
            executor.shutdownNow();
            close(resolver, repository, emsClient);
        }
    }

    @Test
    void refreshCannotPublishAfterShutdownStarts() throws Exception {
        ArtifactFixture artifacts = artifacts();
        MutableEmsClient emsClient = multiRootEmsClient(artifacts);
        AlgorithmRepository repository = repository(
                new DefinitionBackedDownloader(tempDir, artifacts.definitions()));
        LiveSlotGraphResolver resolver = multiRootResolver(repository, emsClient);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            resolver.startAsync().awaitRunning();
            emsClient.setSlot("root-slot", rootSlot(2, artifacts.root()));
            emsClient.blockNextRead("root-slot");
            Future<?> refresh = executor.submit(() -> {
                resolver.refreshNow();
                return null;
            });
            assertTrue(emsClient.awaitBlockedRead());

            Future<?> shutdown = executor.submit(() -> {
                resolver.close();
                return null;
            });
            awaitNotReady(resolver);
            assertFalse(shutdown.isDone());

            emsClient.releaseBlockedRead();
            ExecutionException refreshFailure = assertThrows(
                    ExecutionException.class,
                    () -> refresh.get(10, TimeUnit.SECONDS));
            assertEquals("Live serving graph resolver is closed", refreshFailure.getCause().getMessage());
            shutdown.get(10, TimeUnit.SECONDS);
            assertFalse(resolver.status().ready());
            assertThrows(
                    IllegalStateException.class,
                    () -> invokeRanker(resolver, "root-slot", "search", "customer-1"));
        } finally {
            emsClient.releaseBlockedRead();
            executor.shutdownNow();
            close(resolver, repository, emsClient);
        }
    }

    @Test
    void retiredGraphCleanupRunsOutsideRefreshAndRequestThreads() throws Exception {
        ArtifactFixture artifacts = artifacts();
        AlgorithmMetadata retiringRoot = metadata("retiring-root");
        Map<AlgorithmMetadata, Map<String, String>> definitions = new LinkedHashMap<>(artifacts.definitions());
        definitions.put(retiringRoot, Map.of(
                "retiring-root-algorithm-definition.json",
                definition("retiring-root", BlockingCloseRankerFactory.class, null)));
        MutableEmsClient emsClient = new MutableEmsClient(Map.of("root-slot", rootSlot(retiringRoot)));
        AlgorithmRepository repository = repository(
                new DefinitionBackedDownloader(tempDir, Map.copyOf(definitions)));
        LiveSlotGraphResolver resolver = new LiveSlotGraphResolver(
                Map.of("root-slot", servingSlot("root-slot", "search")),
                repository,
                emsClient,
                Duration.ofMinutes(1));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            BlockingCloseRanker.prepareClose();
            resolver.refreshNow();
            emsClient.setSlot("root-slot", rootSlot(2, artifacts.childA()));
            resolver.refreshNow();

            assertTrue(BlockingCloseRanker.awaitClose());

            Future<AlgorithmExecution<Boolean>> invocation = executor.submit(
                    () -> invokeRanker(resolver, "root-slot", "search", "customer-1"));
            try {
                assertEquals("2", rootVariantId(invocation.get(1, TimeUnit.SECONDS)));
                Future<?> shutdown = executor.submit(() -> {
                    resolver.close();
                    return null;
                });
                awaitNotReady(resolver);
                assertThrows(TimeoutException.class, () -> shutdown.get(100, TimeUnit.MILLISECONDS));
                BlockingCloseRanker.releaseClose();
                shutdown.get(10, TimeUnit.SECONDS);
            } finally {
                BlockingCloseRanker.releaseClose();
            }
        } finally {
            BlockingCloseRanker.releaseClose();
            executor.shutdownNow();
            close(resolver, repository, emsClient);
        }
    }

    @Test
    void inFlightInvocationKeepsItsRetiredGenerationReachable() throws Exception {
        ArtifactFixture artifacts = artifacts();
        AlgorithmMetadata retiringRoot = metadata("retiring-root");
        Map<AlgorithmMetadata, Map<String, String>> definitions = new LinkedHashMap<>(artifacts.definitions());
        definitions.put(retiringRoot, Map.of(
                "retiring-root-algorithm-definition.json",
                definition("retiring-root", BlockingCloseRankerFactory.class, null)));
        MutableEmsClient emsClient = new MutableEmsClient(Map.of("root-slot", rootSlot(retiringRoot)));
        AlgorithmRepository repository = repository(
                new DefinitionBackedDownloader(tempDir, Map.copyOf(definitions)));
        LiveSlotGraphResolver resolver = new LiveSlotGraphResolver(
                Map.of("root-slot", servingSlot("root-slot", "search")),
                repository,
                emsClient,
                Duration.ofMinutes(1));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch invocationStarted = new CountDownLatch(1);
        CountDownLatch invocationRelease = new CountDownLatch(1);

        try {
            BlockingCloseRanker.prepareClose();
            resolver.refreshNow();
            Future<AlgorithmExecution<Boolean>> invocation = executor.submit(() -> resolver.invoke(
                    servingSlot("root-slot", "search"),
                    "customer-1",
                    (Ranker<String, String> ranker) -> {
                        invocationStarted.countDown();
                        try {
                            if (!invocationRelease.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Timed out waiting to release invocation");
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Interrupted while blocking invocation", error);
                        }
                        return true;
                    }));
            assertTrue(invocationStarted.await(10, TimeUnit.SECONDS));

            emsClient.setSlot("root-slot", rootSlot(2, artifacts.childA()));
            resolver.refreshNow();

            assertFalse(BlockingCloseRanker.awaitClose(200, TimeUnit.MILLISECONDS));
            Future<?> shutdown = executor.submit(() -> {
                resolver.close();
                return null;
            });
            awaitNotReady(resolver);
            assertThrows(TimeoutException.class, () -> shutdown.get(100, TimeUnit.MILLISECONDS));
            invocationRelease.countDown();
            assertTrue(invocation.get(10, TimeUnit.SECONDS).result());
            assertTrue(BlockingCloseRanker.awaitClose());
            assertThrows(TimeoutException.class, () -> shutdown.get(100, TimeUnit.MILLISECONDS));
            BlockingCloseRanker.releaseClose();
            shutdown.get(10, TimeUnit.SECONDS);
        } finally {
            invocationRelease.countDown();
            BlockingCloseRanker.releaseClose();
            executor.shutdownNow();
            close(resolver, repository, emsClient);
        }
    }

    @Test
    void repositoryLockDoesNotBlockPredictionOrTheLastRetiredInvocation() throws Exception {
        ArtifactFixture artifacts = artifacts();
        AlgorithmMetadata retiringRoot = metadata("retiring-root");
        Map<AlgorithmMetadata, Map<String, String>> definitions = new LinkedHashMap<>(artifacts.definitions());
        definitions.put(retiringRoot, Map.of(
                "retiring-root-algorithm-definition.json",
                definition("retiring-root", BlockingCloseRankerFactory.class, null)));
        MutableEmsClient emsClient = new MutableEmsClient(Map.of("root-slot", rootSlot(retiringRoot)));
        AlgorithmRepository repository = repository(new DefinitionBackedDownloader(tempDir, definitions));
        LiveSlotGraphResolver resolver = new LiveSlotGraphResolver(
                Map.of("root-slot", servingSlot("root-slot", "search")),
                repository,
                emsClient,
                Duration.ofMinutes(1));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch invocationStarted = new CountDownLatch(1);
        CountDownLatch invocationRelease = new CountDownLatch(1);
        RankingRequest<String, String> request = RankingRequest.ofAvailableActions("test", "shared", List.of());

        try {
            BlockingCloseRanker.prepareClose();
            resolver.refreshNow();
            Future<AlgorithmExecution<RankingResponse<String>>> retiredInvocation = executor.submit(() -> resolver.invoke(
                    servingSlot("root-slot", "search"),
                    "customer-1",
                    (Ranker<String, String> ranker) -> {
                        invocationStarted.countDown();
                        try {
                            assertTrue(invocationRelease.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(error);
                        }
                        return ranker.rank(request);
                    }));
            assertTrue(invocationStarted.await(10, TimeUnit.SECONDS));

            emsClient.setSlot("root-slot", rootSlot(2, artifacts.childA()));
            resolver.refreshNow();

            var lockField = AlgorithmRepository.class.getDeclaredField("lifecycleLock");
            lockField.setAccessible(true);
            synchronized (lockField.get(repository)) {
                // Both request threads must finish while background ownership bookkeeping is blocked.
                invocationRelease.countDown();
                AlgorithmExecution<RankingResponse<String>> old = retiredInvocation.get(1, TimeUnit.SECONDS);
                assertEquals("1", rootVariantId(old));
                assertTrue(old.result().decisions().isEmpty());

                Future<AlgorithmExecution<RankingResponse<String>>> currentInvocation = executor.submit(
                        () -> resolver.invoke(
                                servingSlot("root-slot", "search"),
                                "customer-2",
                                (Ranker<String, String> ranker) -> ranker.rank(request)));
                AlgorithmExecution<RankingResponse<String>> current = currentInvocation.get(1, TimeUnit.SECONDS);
                assertEquals("2", rootVariantId(current));
                assertTrue(current.result().decisions().isEmpty());
                assertFalse(BlockingCloseRanker.awaitClose(100, TimeUnit.MILLISECONDS));
            }
            assertTrue(BlockingCloseRanker.awaitClose());
        } finally {
            invocationRelease.countDown();
            BlockingCloseRanker.releaseClose();
            executor.shutdownNow();
            close(resolver, repository, emsClient);
        }
    }

    @Test
    void statusContainsOnlyOperationalMetadata() throws Exception {
        ArtifactFixture artifacts = artifacts();
        MutableEmsClient emsClient = multiRootEmsClient(artifacts);
        AlgorithmRepository repository = repository(
                new DefinitionBackedDownloader(tempDir, artifacts.definitions()));
        LiveSlotGraphResolver resolver = multiRootResolver(repository, emsClient);

        try {
            resolver.refreshNow();

            ServingRuntimeStatus status = resolver.status();
            Set<String> fields = Arrays.stream(ServingRuntimeStatus.class.getRecordComponents())
                    .map(component -> component.getName())
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(
                    Set.of(
                            "ready",
                            "generation",
                            "activatedAt",
                            "lastPreparationDuration",
                            "assignments",
                            "lastFailure"),
                    fields);
            assertFalse(status.toString().contains("classLoader"));
            assertFalse(status.toString().contains("artifactPath"));
            assertFalse(status.toString().contains("runtimeInstance"));
            assertFalse(status.toString().contains("dependency"));
        } finally {
            close(resolver, repository, emsClient);
        }
    }

    @Test
    void failedInvocationDoesNotKeepARetiredCandidateReachable() throws Exception {
        ArtifactFixture artifacts = artifacts();
        AlgorithmMetadata retiringRoot = metadata("retiring-root");
        Map<AlgorithmMetadata, Map<String, String>> definitions = new LinkedHashMap<>(artifacts.definitions());
        definitions.put(retiringRoot, Map.of(
                "retiring-root-algorithm-definition.json",
                definition("retiring-root", BlockingCloseRankerFactory.class, null)));
        MutableEmsClient emsClient = new MutableEmsClient(Map.of("root-slot", rootSlot(retiringRoot)));
        AlgorithmRepository repository = repository(
                new DefinitionBackedDownloader(tempDir, Map.copyOf(definitions)));
        LiveSlotGraphResolver resolver = new LiveSlotGraphResolver(
                Map.of("root-slot", servingSlot("root-slot", "search")),
                repository,
                emsClient,
                Duration.ofMinutes(1));
        try {
            BlockingCloseRanker.prepareClose();
            resolver.refreshNow();
            IllegalStateException invocationFailure = assertThrows(
                    IllegalStateException.class,
                    () -> resolver.invoke(
                            servingSlot("root-slot", "search"),
                            "customer-1",
                            (Ranker<String, String> ranker) -> {
                                throw new IllegalStateException("invocation failed");
                            }));
            assertEquals("invocation failed", invocationFailure.getMessage());

            emsClient.setSlot("root-slot", rootSlot(2, artifacts.childA()));
            resolver.refreshNow();
            assertTrue(BlockingCloseRanker.awaitClose());
            BlockingCloseRanker.releaseClose();
        } finally {
            BlockingCloseRanker.releaseClose();
            close(resolver, repository, emsClient);
        }
    }

    @Test
    void cleanupFailureWithdrawsTheActiveGenerationAndSurfacesItsCause() throws Exception {
        ArtifactFixture artifacts = artifacts();
        AlgorithmMetadata failingRoot = metadata("failing-close-root");
        Map<AlgorithmMetadata, Map<String, String>> definitions = new LinkedHashMap<>(artifacts.definitions());
        definitions.put(failingRoot, Map.of(
                "failing-close-root-algorithm-definition.json",
                definition("failing-close-root", FailingCloseRankerFactory.class, null)));
        MutableEmsClient emsClient = new MutableEmsClient(Map.of("root-slot", rootSlot(failingRoot)));
        AlgorithmRepository repository = repository(
                new DefinitionBackedDownloader(tempDir, Map.copyOf(definitions)));
        LiveSlotGraphResolver resolver = new LiveSlotGraphResolver(
                Map.of("root-slot", servingSlot("root-slot", "search")),
                repository,
                emsClient,
                Duration.ofMinutes(1));

        try {
            resolver.refreshNow();
            emsClient.setSlot("root-slot", rootSlot(2, artifacts.childA()));
            resolver.refreshNow();
            awaitNotReady(resolver);

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> invokeRanker(resolver, "root-slot", "search", "customer-1"));
            assertEquals("Serving runtime failed", failure.getMessage());
            assertEquals("test cleanup failed", failure.getCause().getMessage());
        } finally {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, () -> close(resolver, repository, emsClient));
            assertEquals("test cleanup failed", failure.getMessage());
        }
    }

    @Test
    void resolvesNestedNamedSlotsAndKeepsTheInvokedGraphAliveAcrossRefreshes() throws Exception {
        ArtifactFixture artifacts = artifacts();
        MutableEmsClient emsClient = new MutableEmsClient(Map.of(
                "root-slot", rootSlot(artifacts.root()),
                "policy-slot", policySlot(artifacts.childA())));
        DefinitionBackedDownloader downloader = new DefinitionBackedDownloader(tempDir, artifacts.definitions());
        AlgorithmRepository repository = repository(downloader);
        LiveSlotGraphResolver resolver = new LiveSlotGraphResolver(
                Map.of("root-slot", servingSlot("root-slot", "search")),
                repository,
                emsClient,
                Duration.ofMinutes(1));

        try {
            BridgeFactory.observedMember.set(null);
            resolver.refreshNow();

            assertEquals(Set.of("root-slot", "policy-slot"), Set.copyOf(emsClient.requestedSlots()));
            assertEquals("child-a", BridgeFactory.observedMember.get());

            AlgorithmExecution<Boolean> execution = resolver.invoke(
                    servingSlot("root-slot", "search"),
                    "customer-1",
                    (Ranker<String, String> ranker) -> {
                        assertDoesNotThrow(resolver::refreshNow);
                        RankingRequest<String, String> request = RankingRequest.ofAvailableActions(
                                "request-1",
                                "shared",
                                List.of());
                        return ranker.rank(request).decisions().isEmpty();
                    });
            assertTrue(execution.result());
            assertEquals("root-slot", execution.selection().rootSlot());
            assertEquals("1", rootVariantId(execution));
            assertEquals(
                    Map.of(
                            "root-slot", new SlotAssignment(
                                    "1",
                                    parameterizedAlgorithm("root")),
                            "policy-slot", new SlotAssignment(
                                    "2",
                                    parameterizedAlgorithm("child-a"))),
                    execution.selection().assignments());

            emsClient.setSlot("root-slot", rootSlot(metadata("missing-root")));
            assertThrows(IllegalArgumentException.class, resolver::refreshNow);

            AlgorithmExecution<Boolean> retained = invokeRanker(
                    resolver,
                    "root-slot",
                    "search",
                    "customer-1");
            assertTrue(retained.result());
            assertEquals("1", rootVariantId(retained));
        } finally {
            try {
                resolver.close();
            } finally {
                try {
                    repository.close();
                } finally {
                    emsClient.close();
                }
            }
        }
    }

    @Test
    void rejectedCandidateDoesNotDisturbTheActiveCandidate() throws Exception {
        ArtifactFixture artifacts = artifacts();
        AlgorithmMetadata failing = metadata("zz-failing");
        Map<AlgorithmMetadata, Map<String, String>> definitions = new LinkedHashMap<>(artifacts.definitions());
        definitions.put(failing, Map.of(
                "zz-failing-algorithm-definition.json",
                definition("zz-failing", FailingFactory.class, null)));
        MutableEmsClient emsClient = new MutableEmsClient(Map.of(
                "root-slot", rootSlot(artifacts.root()),
                "policy-slot", policySlot(artifacts.childA())));
        AlgorithmRepository repository = repository(
                new DefinitionBackedDownloader(tempDir, Map.copyOf(definitions)));
        LiveSlotGraphResolver resolver = new LiveSlotGraphResolver(
                Map.of("root-slot", servingSlot("root-slot", "search")),
                repository,
                emsClient,
                Duration.ofMinutes(1));

        try {
            resolver.refreshNow();
            emsClient.setSlot("policy-slot", policySlot(failing));

            assertThrows(RuntimeException.class, resolver::refreshNow);

            assertTrue(invokeRanker(resolver, "root-slot", "search", "customer-1").result());
        } finally {
            try {
                resolver.close();
            } finally {
                try {
                    repository.close();
                } finally {
                    emsClient.close();
                }
            }
        }
    }

    @Test
    void assignsExperimentVariantsByVariantIdRatherThanTheirPayloadOrder() throws Exception {
        ArtifactFixture artifacts = artifacts();
        MutableEmsClient emsClient = new MutableEmsClient(Map.of(
                "root-slot", unorderedExperimentSlot(artifacts.childA(), artifacts.childB())));
        AlgorithmRepository repository = repository(
                new DefinitionBackedDownloader(tempDir, artifacts.definitions()));
        LiveSlotGraphResolver resolver = new LiveSlotGraphResolver(
                Map.of("root-slot", servingSlot("root-slot", "search")),
                repository,
                emsClient,
                Duration.ofMinutes(1));

        try {
            resolver.refreshNow();

            assertEquals(
                    "1",
                    rootVariantId(invokeRanker(
                            resolver,
                            "root-slot",
                            "search",
                            assignmentKeyForAllocationBucket("root-salt", 9, 0, 2))));
        } finally {
            try {
                resolver.close();
            } finally {
                try {
                    repository.close();
                } finally {
                    emsClient.close();
                }
            }
        }
    }

    private MutableEmsClient multiRootEmsClient(ArtifactFixture artifacts) {
        return new MutableEmsClient(Map.of(
                "root-slot", rootSlot(artifacts.root()),
                "other-root", rootSlot(artifacts.childA()),
                "policy-slot", policySlot(artifacts.childA())));
    }

    private LiveSlotGraphResolver multiRootResolver(
            AlgorithmRepository repository,
            MutableEmsClient emsClient) {
        return new LiveSlotGraphResolver(
                Map.of(
                        "root-slot", servingSlot("root-slot", "search"),
                        "other-root", servingSlot("other-root", "homepage")),
                repository,
                emsClient,
                Duration.ofMinutes(1));
    }

    private static WideFixture wideFixture(int rootCount, int dependencySlotCount) {
        AlgorithmMetadata childA = metadata("wide-child-a");
        AlgorithmMetadata childB = metadata("wide-child-b");
        Map<AlgorithmMetadata, Map<String, String>> definitions = new LinkedHashMap<>();
        definitions.put(childA, Map.of(
                "wide-child-a-algorithm-definition.json",
                definition("wide-child-a", ChildFactory.class, null)));
        definitions.put(childB, Map.of(
                "wide-child-b-algorithm-definition.json",
                definition("wide-child-b", ChildFactory.class, null)));

        Map<String, Slot> emsSlots = new LinkedHashMap<>();
        for (int index = 0; index < dependencySlotCount; index++) {
            String slotName = "policy-" + index;
            emsSlots.put(slotName, binarySlot(slotName, index, childA, childB));
        }

        Map<String, ServingSlot> servingSlots = new LinkedHashMap<>();
        String dependencies = slotDependencies(dependencySlotCount);
        for (int index = 0; index < rootCount; index++) {
            String rootSlotName = "root-" + index;
            String algorithmName = "wide-root-" + index;
            AlgorithmMetadata root = metadata(algorithmName);
            definitions.put(root, Map.of(
                    algorithmName + "-algorithm-definition.json",
                    definition(algorithmName, WideRootFactory.class, dependencies)));
            emsSlots.put(rootSlotName, rootSlot(root));
            servingSlots.put(rootSlotName, servingSlot(rootSlotName, "touchpoint-" + index));
        }
        return new WideFixture(
                Map.copyOf(definitions),
                Map.copyOf(emsSlots),
                Map.copyOf(servingSlots));
    }

    private static Slot binarySlot(
            String slotName,
            int index,
            AlgorithmMetadata defaultAlgorithm,
            AlgorithmMetadata experimentAlgorithm) {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        return new Slot(
                slotName + "-salt",
                2,
                new Variant(1, defaultAlgorithm, createdAt, false, true, 1),
                List.of(new Experiment(
                        1000 + index,
                        slotName + "-experiment",
                        List.of(new Variant(2, experimentAlgorithm, createdAt, false, false, 1)),
                        100,
                        List.of(new Shard(1, createdAt)))),
                List.of());
    }

    private static String slotDependencies(int dependencySlotCount) {
        StringBuilder dependencies = new StringBuilder("\"dependencies\": {");
        for (int index = 0; index < dependencySlotCount; index++) {
            if (index > 0) {
                dependencies.append(',');
            }
            dependencies.append("\"policy-")
                    .append(index)
                    .append("\": {\"scope\": \"slot\"}");
        }
        return dependencies.append('}').toString();
    }

    private static AlgorithmExecution<Boolean> invokeRanker(
            LiveSlotGraphResolver resolver,
            String rootSlot,
            String touchpoint,
            String assignmentKey) {
        return resolver.invoke(
                servingSlot(rootSlot, touchpoint),
                assignmentKey,
                (Ranker<String, String> ranker) -> true);
    }

    private static String rootVariantId(AlgorithmExecution<?> execution) {
        AlgorithmSelection selection = execution.selection();
        return selection.assignments().get(selection.rootSlot()).variantId();
    }

    private static ServingSlot servingSlot(String name, String touchpoint) {
        return ServingSlot.builder(name, new TypeToken<Ranker<String, String>>() {})
                .touchpoints(Set.of(touchpoint))
                .build();
    }

    private static AlgorithmRepository repository(AlgorithmDownloader downloader) {
        return new AlgorithmRepository(downloader, null, AlgorithmDependencies.empty());
    }

    private static void close(
            LiveSlotGraphResolver resolver,
            AlgorithmRepository repository,
            MutableEmsClient emsClient) throws Exception {
        try {
            resolver.close();
        } finally {
            try {
                repository.close();
            } finally {
                emsClient.close();
            }
        }
    }

    private static void awaitNotReady(LiveSlotGraphResolver resolver) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (resolver.status().ready() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertFalse(resolver.status().ready());
    }

    private ArtifactFixture artifacts() {
        AlgorithmMetadata root = metadata("root");
        AlgorithmMetadata childA = metadata("child-a");
        AlgorithmMetadata childB = metadata("child-b");
        AlgorithmMetadata unusedRoot = metadata("unused-root");
        AlgorithmMetadata badRoot = metadata("bad-root");
        Map<AlgorithmMetadata, Map<String, String>> definitions = new LinkedHashMap<>();
        definitions.put(root, Map.of(
                "root-algorithm-definition.json", definition(
                        "root",
                        RootFactory.class,
                        "\"dependencies\": [\"bridge\"]"),
                "bridge-algorithm-definition.json", definition(
                        "bridge",
                        BridgeFactory.class,
                        "\"dependencies\": {\"policy-slot\": {\"scope\": \"slot\"}}")));
        definitions.put(childA, Map.of(
                "child-a-algorithm-definition.json", definition("child-a", ChildFactory.class, null)));
        definitions.put(childB, Map.of(
                "child-b-algorithm-definition.json", definition("child-b", ChildFactory.class, null)));
        definitions.put(unusedRoot, Map.of(
                "unused-root-algorithm-definition.json", definition("unused-root", RootFactory.class, null)));
        definitions.put(badRoot, Map.of(
                "bad-root-algorithm-definition.json", definition("bad-root", BadRootFactory.class, null)));
        return new ArtifactFixture(root, childA, childB, unusedRoot, badRoot, Map.copyOf(definitions));
    }

    private static String definition(String name, Class<?> factoryClass, String dependencies) {
        String dependencyField = dependencies == null ? "" : ",\n  " + dependencies;
        return """
                {
                  "algorithm_name": "%s",
                  "algorithm_version": "1",
                  "algorithm_factory_classname": "%s"%s
                }
                """.formatted(name, factoryClass.getName(), dependencyField);
    }

    private static ParameterizedAlgorithmId parameterizedAlgorithm(String name) {
        return new ParameterizedAlgorithmId(
                new HyperparameterizedAlgorithmId(new AlgorithmId(name, "1"), null),
                ParameterizedAlgorithmId.NO_PARAMETER_ID);
    }

    private static AlgorithmMetadata metadata(String name) {
        return new AlgorithmMetadata(
                name,
                "1",
                null,
                "s3://test/" + name + ".jar",
                null);
    }

    private static Slot rootSlot(AlgorithmMetadata root) {
        return rootSlot(1, root);
    }

    private static Slot rootSlot(int variantId, AlgorithmMetadata root) {
        return new Slot(
                "root-salt",
                100,
                new Variant(variantId, root, Instant.parse("2026-01-01T00:00:00Z"), false, true, 100),
                List.of(),
                List.of());
    }

    private static Slot policySlot(AlgorithmMetadata child) {
        return new Slot(
                "policy-salt",
                100,
                new Variant(
                        2,
                        child,
                        Instant.parse("2026-01-01T00:00:00Z"),
                        false,
                        true,
                        100),
                List.of(),
                List.of());
    }

    private static Slot unorderedExperimentSlot(AlgorithmMetadata childA, AlgorithmMetadata childB) {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        return new Slot(
                "root-salt",
                1,
                new Variant(3, childA, createdAt, false, true, 100),
                List.of(new Experiment(
                        9,
                        "payload-order-must-not-control-assignment",
                        List.of(
                                new Variant(2, childB, createdAt, false, false, 1),
                                new Variant(1, childA, createdAt, false, false, 1)),
                        100,
                        List.of(new Shard(1, createdAt)))),
                List.of());
    }

    @SuppressWarnings("deprecation")
    private static String assignmentKeyForAllocationBucket(
            String slotSalt,
            int experimentId,
            int expectedBucket,
            int totalAllocation) {
        for (int index = 0; ; index++) {
            String key = "customer-" + index;
            int hash = Hashing.md5().hashString(
                    slotSalt + experimentId + key,
                    StandardCharsets.UTF_8).asInt();
            if (Math.abs(hash) % totalAllocation == expectedBucket) {
                return key;
            }
        }
    }

    private record ArtifactFixture(
            AlgorithmMetadata root,
            AlgorithmMetadata childA,
            AlgorithmMetadata childB,
            AlgorithmMetadata unusedRoot,
            AlgorithmMetadata badRoot,
            Map<AlgorithmMetadata, Map<String, String>> definitions) {
    }

    private record WideFixture(
            Map<AlgorithmMetadata, Map<String, String>> definitions,
            Map<String, Slot> emsSlots,
            Map<String, ServingSlot> servingSlots) {
    }

    private static final class MutableEmsClient extends ExperimentManagementServiceClient {
        private final Map<String, Slot> slots = new LinkedHashMap<>();
        private final List<String> requestedSlots = new java.util.ArrayList<>();
        private final Set<String> failingSlots = new HashSet<>();
        private volatile String blockedSlot;
        private CountDownLatch blockedReadStarted;
        private CountDownLatch blockedReadRelease;

        private MutableEmsClient(Map<String, Slot> slots) {
            super(
                    URI.create("http://ems.invalid/"),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    () -> null);
            this.slots.putAll(slots);
        }

        @Override
        public Slot getDefaultVariantAndActiveExperiments(String slotName) throws IOException {
            requestedSlots.add(slotName);
            if (slotName.equals(blockedSlot)) {
                blockedSlot = null;
                blockedReadStarted.countDown();
                try {
                    if (!blockedReadRelease.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release EMS read for " + slotName);
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while blocking EMS read for " + slotName, error);
                }
            }
            if (failingSlots.contains(slotName)) {
                throw new IOException("EMS read rejected for " + slotName);
            }
            Slot slot = slots.get(slotName);
            if (slot == null) {
                throw new IOException("Unknown EMS slot " + slotName);
            }
            return slot;
        }

        private void setSlot(String name, Slot slot) {
            slots.put(name, slot);
        }

        private void failSlot(String slotName) {
            failingSlots.add(slotName);
        }

        private void clearRequestedSlots() {
            requestedSlots.clear();
        }

        private void blockNextRead(String slotName) {
            blockedReadStarted = new CountDownLatch(1);
            blockedReadRelease = new CountDownLatch(1);
            blockedSlot = slotName;
        }

        private boolean awaitBlockedRead() throws InterruptedException {
            return blockedReadStarted.await(10, TimeUnit.SECONDS);
        }

        private void releaseBlockedRead() {
            blockedReadRelease.countDown();
        }

        private List<String> requestedSlots() {
            return List.copyOf(requestedSlots);
        }
    }

    private static final class DefinitionBackedDownloader extends AlgorithmDownloader {
        private final Path scratchDirectory;
        private final Map<AlgorithmMetadata, Map<String, String>> definitions;
        private int artifactCount;
        private int graphLoadCount;

        private DefinitionBackedDownloader(
                Path scratchDirectory,
                Map<AlgorithmMetadata, Map<String, String>> definitions) {
            super(
                    null,
                    scratchDirectory,
                    LiveSlotGraphResolverTest.class.getClassLoader(),
                    new AlgorithmDownloader.Options(
                            InputSemantic.ONLINE,
                            false,
                            false,
                            Optional.empty()));
            this.scratchDirectory = scratchDirectory;
            this.definitions = definitions;
        }

        @Override
        public DownloadedAlgorithmArtifact downloadAlgorithmArtifact(AlgorithmMetadata metadata) {
            Map<String, String> artifactDefinitions = definitions.get(metadata);
            if (artifactDefinitions == null) {
                throw new IllegalArgumentException("No test artifact for " + metadata.algorithmId());
            }
            try {
                Files.createDirectories(scratchDirectory);
                Path jar = scratchDirectory.resolve("artifact-" + (++artifactCount) + ".jar");
                try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
                    for (Map.Entry<String, String> definition : artifactDefinitions.entrySet()) {
                        output.putNextEntry(new JarEntry(definition.getKey()));
                        output.write(definition.getValue().getBytes(StandardCharsets.UTF_8));
                        output.closeEntry();
                    }
                }
                return DownloadedAlgorithmArtifacts.create(
                        new AlgorithmInstanceFactory(
                                jar.toFile(),
                                LiveSlotGraphResolverTest.class.getClassLoader(),
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
        public AlgorithmGraph<?> loadAlgorithmGraph(
                AlgorithmMetadata metadata,
                DownloadedAlgorithmArtifact artifact,
                File parameterFile,
                AlgorithmDependencies hostBindings,
                AlgorithmGraphDependencies slotBindings,
                String rootProviderIdentity,
                Map<AlgorithmId, AlgorithmArtifactProvider> sharedProviders,
                SharedNodeInterner sharedNodeInterner,
                ExecutionContext executionContext) {
            graphLoadCount++;
            return super.loadAlgorithmGraph(
                    metadata,
                    artifact,
                    parameterFile,
                    hostBindings,
                    slotBindings,
                    rootProviderIdentity,
                    sharedProviders,
                    sharedNodeInterner,
                    executionContext);
        }

        private int graphLoadCount() {
            return graphLoadCount;
        }
    }

    public static final class RootFactory implements CompositeAlgorithmFactory<Ranker<String, String>> {
        @Override
        public Ranker<String, String> create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<JsonNode> hyperparameters,
                Map<String, java.io.InputStream> parameters,
                AlgorithmDependencies dependencies) {
            dependencies.only("bridge", new TypeToken<Ranker<String, String>>() {});
            return new TestRanker("root");
        }
    }

    public static final class WideRootFactory implements CompositeAlgorithmFactory<Ranker<String, String>> {
        @Override
        public Ranker<String, String> create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<JsonNode> hyperparameters,
                Map<String, java.io.InputStream> parameters,
                AlgorithmDependencies dependencies) {
            return new TestRanker("wide-root");
        }
    }

    public static final class BridgeFactory implements CompositeAlgorithmFactory<Ranker<String, String>> {
        private static final AtomicReference<String> observedMember = new AtomicReference<>();

        @Override
        public Ranker<String, String> create(
                ExecutionContext executionContext,
                Optional<LocalStateStorage> localStateStorage,
                Optional<JsonNode> hyperparameters,
                Map<String, java.io.InputStream> parameters,
                AlgorithmDependencies dependencies) {
            String member = dependencies.asMap().get("policy-slot")
                    .algorithmDefinition()
                    .algorithmId()
                    .algorithmName();
            if (!member.equals("child-a")) {
                throw new IllegalStateException("Unexpected policy member " + member);
            }
            observedMember.set(member);
            return new TestRanker("bridge");
        }
    }

    public static final class ChildFactory implements SimpleRankerFactory<String, String> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<String, String> apply(Optional<JsonNode> hyperparameters) {
            return new TestRanker("child");
        }
    }

    public static final class BlockingCloseRankerFactory implements SimpleRankerFactory<String, String> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<String, String> apply(Optional<JsonNode> hyperparameters) {
            return new BlockingCloseRanker();
        }
    }

    public static final class BadRootFactory implements SimpleTopKFactory<String, String> {
        @Override
        @SuppressWarnings("removal")
        public TopK<String, String> apply(Optional<JsonNode> hyperparameters) {
            return new ClosingTopK();
        }
    }

    public static final class FailingFactory implements SimpleRankerFactory<String, String> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<String, String> apply(Optional<JsonNode> hyperparameters) {
            throw new IllegalStateException("Rejected test candidate");
        }
    }

    public static final class FailingCloseRankerFactory implements SimpleRankerFactory<String, String> {
        @Override
        @SuppressWarnings("removal")
        public Ranker<String, String> apply(Optional<JsonNode> hyperparameters) {
            return new FailingCloseRanker();
        }
    }

    private record TestRanker(String name) implements Ranker<String, String> {
        @Override
        public RankingResponse<String> rank(RankingRequest<String, String> request) {
            return RankingResponse.newResponse(List.of());
        }
    }

    private static final class FailingCloseRanker implements Ranker<String, String> {
        @Override
        public RankingResponse<String> rank(RankingRequest<String, String> request) {
            return RankingResponse.newResponse(List.of());
        }

        @Override
        public void close() {
            throw new IllegalStateException("test cleanup failed");
        }
    }

    private static final class BlockingCloseRanker implements Ranker<String, String> {
        private static final AtomicReference<CloseControl> currentControl = new AtomicReference<>();
        private final CloseControl closeControl = currentControl.get();

        private static void prepareClose() {
            currentControl.set(new CloseControl(new CountDownLatch(1), new CountDownLatch(1)));
        }

        private static boolean awaitClose() throws InterruptedException {
            return currentControl.get().started().await(10, TimeUnit.SECONDS);
        }

        private static boolean awaitClose(long timeout, TimeUnit unit) throws InterruptedException {
            return currentControl.get().started().await(timeout, unit);
        }

        private static void releaseClose() {
            CloseControl control = currentControl.get();
            if (control != null) {
                control.release().countDown();
            }
        }

        @Override
        public RankingResponse<String> rank(RankingRequest<String, String> request) {
            return RankingResponse.newResponse(List.of());
        }

        @Override
        public void close() {
            closeControl.started().countDown();
            try {
                if (!closeControl.release().await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release algorithm close");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while blocking algorithm close", error);
            }
        }

        private record CloseControl(CountDownLatch started, CountDownLatch release) {
        }
    }

    private static final class ClosingTopK implements TopK<String, String> {
        private static final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public TopKResponse<String> apply(TopKRequest<String> request) {
            return TopKResponse.newResponse(List.of());
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }
}
