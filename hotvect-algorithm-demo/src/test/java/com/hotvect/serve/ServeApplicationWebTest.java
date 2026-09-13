package com.hotvect.serve;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ServeApplicationWebTest {
    @Test
    void servesCoreAndExtensionRoutesOnVirtualThreads() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        AtomicBoolean extensionClosed = new AtomicBoolean();
        @RestController
        class TestExtension implements ServerExtension {
            @GetMapping("/test/virtual-thread")
            Map<String, Boolean> virtualThread() {
                return Map.of("virtual", Thread.currentThread().isVirtual());
            }

            @Override
            public void close() {
                extensionClosed.set(true);
            }
        }

        ServerOptions options = new ServerOptions();
        options.host = "127.0.0.1";
        options.port = port;
        options.maxRequestMiB = 1;
        LocalRuntimeCatalog runtimeCatalog = mock(LocalRuntimeCatalog.class);

        try (ServeApplication application = new ServeApplication(
                options,
                List.of(new TestExtension()),
                false,
                runtimeCatalog)) {
            application.start();
            HttpClient client = HttpClient.newHttpClient();

            assertEquals(404, get(client, port, "/").statusCode());
            assertEquals(404, get(client, port, "/index.html").statusCode());
            assertEquals(200, get(client, port, "/health").statusCode());
            assertEquals(200, get(client, port, "/actuator/health").statusCode());
            assertEquals(404, get(client, port, "/api/health").statusCode());
            assertEquals(404, get(client, port, "/api/config").statusCode());
            HttpResponse<String> extensionResponse = get(client, port, "/test/virtual-thread");
            assertEquals(200, extensionResponse.statusCode());
            assertEquals("{\"virtual\":true}", extensionResponse.body());
        }

        verify(runtimeCatalog).close();
        assertTrue(extensionClosed.get());
    }

    @Test
    void servesBundledUiWhenStaticResourcesAreEnabled() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        ServerOptions options = new ServerOptions();
        options.host = "127.0.0.1";
        options.port = port;
        options.maxRequestMiB = 1;
        LocalRuntimeCatalog runtimeCatalog = mock(LocalRuntimeCatalog.class);

        try (ServeApplication application = new ServeApplication(
                options,
                List.of(),
                true,
                runtimeCatalog)) {
            application.start();

            HttpResponse<String> response = get(HttpClient.newHttpClient(), port, "/");
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("Hotvect"));
        }

        verify(runtimeCatalog).close();
    }

    private static HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
