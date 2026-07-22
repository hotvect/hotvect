package com.hotvect.onlineutils.experimentmanagement.algodownload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Utilities;

class S3AlgorithmDownloadClientTest {

    @Test
    void closeDoesNotCloseInjectedClientByDefault() {
        final AtomicInteger closeCalls = new AtomicInteger();
        final S3AsyncClient s3Client = testS3Client(closeCalls);

        new S3AlgorithmDownloadClient(s3Client).close();

        assertEquals(0, closeCalls.get());
    }

    @Test
    void closeCanOwnAndCloseInjectedClientWhenExplicitlyRequested() {
        final AtomicInteger closeCalls = new AtomicInteger();
        final S3AsyncClient s3Client = testS3Client(closeCalls);

        new S3AlgorithmDownloadClient(s3Client, true).close();

        assertEquals(1, closeCalls.get());
    }

    @Test
    void executionFailureDoesNotInterruptCurrentThread() {
        Thread.interrupted();
        final TestableS3AlgorithmDownloadClient client = new TestableS3AlgorithmDownloadClient(
                testS3Client(
                        new AtomicInteger(),
                        CompletableFuture.failedFuture(new IllegalStateException("boom"))));

        assertThrows(
                RuntimeException.class,
                () -> client.invokeDownloadFile(
                        Path.of(System.getProperty("java.io.tmpdir"), "algorithm.zip"),
                        "s3://bucket/key"));

        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void interruptedWaitRestoresInterruptFlag() {
        Thread.interrupted();
        final TestableS3AlgorithmDownloadClient client = new TestableS3AlgorithmDownloadClient(
                testS3Client(new AtomicInteger(), new CompletableFuture<>()));

        try {
            Thread.currentThread().interrupt();

            assertThrows(
                    RuntimeException.class,
                    () -> client.invokeDownloadFile(
                            Path.of(System.getProperty("java.io.tmpdir"), "algorithm.zip"),
                            "s3://bucket/key"));

            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static S3AsyncClient testS3Client(final AtomicInteger closeCalls) {
        return testS3Client(closeCalls, new CompletableFuture<>());
    }

    private static S3AsyncClient testS3Client(
            final AtomicInteger closeCalls,
            final CompletableFuture<?> getObjectFuture) {
        final S3Utilities s3Utilities = S3Utilities.builder()
                .region(Region.EU_CENTRAL_1)
                .fipsEnabled(false)
                .dualstackEnabled(false)
                .build();
        return (S3AsyncClient) Proxy.newProxyInstance(
                S3AsyncClient.class.getClassLoader(),
                new Class<?>[] {S3AsyncClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getObject" -> getObjectFuture;
                    case "utilities" -> s3Utilities;
                    case "close" -> {
                        closeCalls.incrementAndGet();
                        yield null;
                    }
                    case "serviceName" -> "s3";
                    case "toString" -> "test-s3-client";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("Unexpected method: " + method.getName());
                });
    }

    private static final class TestableS3AlgorithmDownloadClient extends S3AlgorithmDownloadClient {
        private TestableS3AlgorithmDownloadClient(final S3AsyncClient s3Client) {
            super(s3Client);
        }

        private void invokeDownloadFile(final Path destination, final String s3Uri) {
            downloadFile(destination, s3Uri);
        }
    }
}
