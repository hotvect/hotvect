package com.hotvect.onlineutils.experimentmanagement.httpclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hotvect.onlineutils.experimentmanagement.models.Slot;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExperimentManagementServiceClientTest {

    private static final Duration EMS_CLIENT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration EMS_CLIENT_READ_TIMEOUT = Duration.ofSeconds(2);

    @Test
    void fetchesSlotPayloadAndSendsExpectedHeaders() throws Exception {
        try (TestEmsServer server = TestEmsServer.responding(200, slotPayload())) {
            final ExperimentManagementServiceClient emsClient = new ExperimentManagementServiceClient(
                    server.baseUri(),
                    EMS_CLIENT_CONNECT_TIMEOUT,
                    EMS_CLIENT_READ_TIMEOUT,
                    () -> "test-token");

            final Slot slot = emsClient.getDefaultVariantAndActiveExperiments("catalog relevance");

            assertEquals("salt-1", slot.slotSalt());
            assertEquals(100, slot.totalNumberOfShards());
            assertEquals("/slots/catalog%20relevance/defaultVariantAndActiveExperiments", server.requestPath());
            assertEquals("GET", server.requestMethod());
            assertEquals("application/json", server.header("Accept"));
            assertEquals("Bearer test-token", server.header("Authorization"));
        }
    }

    @Test
    void omitsAuthorizationHeaderWhenTokenIsBlank() throws Exception {
        try (TestEmsServer server = TestEmsServer.responding(200, slotPayload())) {
            final Supplier<String> blankTokenSupplier = () -> " ";
            final ExperimentManagementServiceClient emsClient = new ExperimentManagementServiceClient(
                    server.baseUri(),
                    EMS_CLIENT_CONNECT_TIMEOUT,
                    EMS_CLIENT_READ_TIMEOUT,
                    blankTokenSupplier);

            emsClient.getDefaultVariantAndActiveExperiments("catalog");

            assertEquals(List.of(), server.headers().getOrDefault("Authorization", List.of()));
        }
    }

    @Test
    void throwsWhenEmsReturnsNonSuccessStatus() throws Exception {
        try (TestEmsServer server = TestEmsServer.responding(500, "{\"message\":\"bad slot\"}")) {
            final ExperimentManagementServiceClient emsClient = new ExperimentManagementServiceClient(
                    server.baseUri(),
                    EMS_CLIENT_CONNECT_TIMEOUT,
                    EMS_CLIENT_READ_TIMEOUT,
                    () -> "test-token");

            final RuntimeException exception = assertThrows(
                    RuntimeException.class,
                    () -> emsClient.getDefaultVariantAndActiveExperiments("missing-slot"));

            assertEquals(
                    "Call to EMS failed with error message: {\"message\":\"bad slot\"}",
                    exception.getMessage());
        }
    }

    private static String slotPayload() {
        return """
                {
                  "slot_salt": "salt-1",
                  "total_number_of_shards": 100,
                  "default_variant": {
                    "variant_id": 1,
                    "algorithm": {
                      "algorithm_name": "algo-a",
                      "algorithm_version": "1.0.0",
                      "latest_algorithm_parameter": "param-1",
                      "absolute_s3_algorithm_jar_path": "s3://bucket/algo-a.jar",
                      "absolute_s3_algorithm_parameter_path": "s3://bucket/param-1.zip"
                    },
                    "created_at": "2026-04-11T10:15:30Z",
                    "is_control": true,
                    "is_default": true,
                    "shard_allocation_ratio": 100
                  },
                  "experiments": [],
                  "user_forced_assignments": []
                }
                """;
    }

    private static final class TestEmsServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<CapturedRequest> request = new AtomicReference<>();

        private TestEmsServer(final HttpServer server) {
            this.server = server;
        }

        private static TestEmsServer responding(final int statusCode, final String responseBody) throws Exception {
            final HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    0);
            final TestEmsServer testServer = new TestEmsServer(server);
            server.createContext("/", exchange -> testServer.handle(exchange, statusCode, responseBody));
            server.start();
            return testServer;
        }

        private void handle(
                final HttpExchange exchange,
                final int statusCode,
                final String responseBody) throws java.io.IOException {
            request.set(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    Map.copyOf(exchange.getRequestHeaders())));
            final byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream responseStream = exchange.getResponseBody()) {
                responseStream.write(responseBytes);
            }
        }

        private URI baseUri() {
            return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
        }

        private String requestMethod() {
            return request.get().method();
        }

        private String requestPath() {
            return request.get().path();
        }

        private String header(final String name) {
            final List<String> values = headers().get(name);
            return values == null || values.isEmpty() ? null : values.getFirst();
        }

        private Map<String, List<String>> headers() {
            return request.get().headers();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private record CapturedRequest(String method, String path, Map<String, List<String>> headers) {
    }
}
