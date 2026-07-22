package com.hotvect.onlineutils.experimentmanagement.algodownload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmInstance;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.api.algorithms.Algorithm;
import com.hotvect.api.execution.ExecutionContext;
import com.hotvect.api.execution.InputSemantic;
import com.hotvect.onlineutils.experimentmanagement.models.AlgorithmMetadata;
import com.hotvect.onlineutils.hotdeploy.AlgorithmInstanceFactory;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AlgorithmRepositoryTest {

    @Test
    void reusesLiveAlgorithmInstance() throws Exception {
        CountingAlgorithmDownloader downloader = new CountingAlgorithmDownloader();
        AlgorithmRepository repository = new AlgorithmRepository(downloader);
        AlgorithmMetadata algorithmMetadata = algorithmMetadata("parameter-1");

        AlgorithmInstance<?> firstInstance = repository.getAlgorithmInstance(algorithmMetadata);
        AlgorithmInstance<?> secondInstance = repository.getAlgorithmInstance(algorithmMetadata);

        assertSame(firstInstance, secondInstance);
        assertEquals(1, downloader.getJarDownloadCount());
        assertEquals(1, downloader.getAlgorithmLoadCount());
    }

    @Test
    void reloadsNewParameterForSameAlgorithmVersion() throws Exception {
        CountingAlgorithmDownloader downloader = new CountingAlgorithmDownloader();
        AlgorithmRepository repository = new AlgorithmRepository(downloader);

        AlgorithmInstance<?> firstInstance =
                repository.getAlgorithmInstance(algorithmMetadata("parameter-1"));
        AlgorithmInstance<?> secondInstance =
                repository.getAlgorithmInstance(algorithmMetadata("parameter-2"));

        assertEquals("parameter-1", firstInstance.algorithmParameterMetadata().parameterId());
        assertEquals("parameter-2", secondInstance.algorithmParameterMetadata().parameterId());
        assertEquals(1, downloader.getJarDownloadCount());
        assertEquals(2, downloader.getAlgorithmLoadCount());
    }

    @Test
    void closesAlgorithmAfterAlgorithmInstanceBecomesUnreachable() throws Exception {
        CountingAlgorithmDownloader downloader = new CountingAlgorithmDownloader();
        AlgorithmRepository repository = new AlgorithmRepository(downloader);
        CapturedAlgorithm capturedAlgorithm = captureAlgorithm(repository, algorithmMetadata("parameter-1"));

        waitForCleanup(capturedAlgorithm);

        assertNull(capturedAlgorithm.algorithmInstanceReference().get());
        assertTrue(capturedAlgorithm.algorithm().awaitClosed());
        assertEquals(1, capturedAlgorithm.algorithm().getCloseCallCount());
    }

    private static CapturedAlgorithm captureAlgorithm(
            final AlgorithmRepository repository,
            final AlgorithmMetadata algorithmMetadata) {
        AlgorithmInstance<?> algorithmInstance = repository.getAlgorithmInstance(algorithmMetadata);
        assertNotNull(algorithmInstance);
        TestAlgorithm algorithm = assertInstanceOf(TestAlgorithm.class, algorithmInstance.algorithm());
        return new CapturedAlgorithm(new WeakReference<>(algorithmInstance), algorithm);
    }

    private static void waitForCleanup(final CapturedAlgorithm capturedAlgorithm) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((capturedAlgorithm.algorithmInstanceReference().get() != null
                || !capturedAlgorithm.algorithm().isClosed())
                && System.nanoTime() < deadlineNanos) {
            System.gc();
            Thread.sleep(50);
        }
    }

    private static AlgorithmMetadata algorithmMetadata(final String parameterId) {
        return new AlgorithmMetadata(
                "test-algorithm",
                "1.0.0",
                parameterId,
                "s3://bucket/test-algorithm.jar",
                "s3://bucket/" + parameterId + ".zip");
    }

    private record CapturedAlgorithm(
            WeakReference<AlgorithmInstance<?>> algorithmInstanceReference,
            TestAlgorithm algorithm) {
    }

    private static final class CountingAlgorithmDownloader extends AlgorithmDownloader {
        private final AtomicInteger jarDownloadCount = new AtomicInteger();
        private final AtomicInteger algorithmLoadCount = new AtomicInteger();
        private final AlgorithmInstanceFactory algorithmInstanceFactory;

        private CountingAlgorithmDownloader() throws MalformedAlgorithmException {
            super(null, System.getProperty("java.io.tmpdir"), AlgorithmRepositoryTest.class.getClassLoader(), false);
            this.algorithmInstanceFactory = new AlgorithmInstanceFactory(
                    AlgorithmRepositoryTest.class.getClassLoader(),
                    ExecutionContext.realtime(InputSemantic.ONLINE),
                    false);
        }

        @Override
        public AlgorithmInstanceFactory downloadAlgorithmJar(final AlgorithmMetadata algorithmMetadata) {
            jarDownloadCount.incrementAndGet();
            return algorithmInstanceFactory;
        }

        @Override
        public AlgorithmInstance<TestAlgorithm> loadAlgorithmInstance(
                final AlgorithmMetadata algorithmMetadata,
                final AlgorithmInstanceFactory algorithmHolder,
                final Map<String, AlgorithmInstance<?>> dependencyOverrides) {
            algorithmLoadCount.incrementAndGet();
            return new AlgorithmInstance<>(
                    new AlgorithmDefinition(
                            JsonNodeFactory.instance.objectNode(),
                            algorithmMetadata.algorithmId(),
                            ImmutableMap.of(),
                            ImmutableMap.of(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()),
                    new AlgorithmParameterMetadata(
                            new AlgorithmId(algorithmMetadata.algorithmName(), algorithmMetadata.algorithmVersion()),
                            algorithmMetadata.latestAlgorithmParameter(),
                            Instant.now(),
                            Optional.empty()),
                    new TestAlgorithm());
        }

        private int getJarDownloadCount() {
            return jarDownloadCount.get();
        }

        private int getAlgorithmLoadCount() {
            return algorithmLoadCount.get();
        }
    }

    private static final class TestAlgorithm implements Algorithm {
        private final CountDownLatch closeLatch = new CountDownLatch(1);
        private final AtomicInteger closeCallCount = new AtomicInteger();

        @Override
        public void close() {
            closeCallCount.incrementAndGet();
            closeLatch.countDown();
        }

        private boolean isClosed() {
            return closeLatch.getCount() == 0;
        }

        private boolean awaitClosed() throws InterruptedException {
            return closeLatch.await(1, TimeUnit.SECONDS);
        }

        private int getCloseCallCount() {
            return closeCallCount.get();
        }
    }
}
